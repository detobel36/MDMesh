import { useEffect, useRef, useState } from 'react';
import {
  startRemoteSession,
  sendRemoteSignal,
  getRemoteSignals,
  stopRemoteSession,
  type RemoteSignal,
} from '../api/remote';
import { useToast } from '../ui/toast';

interface Props {
  deviceNumber: string;
  onClose: () => void;
}

export function ScreenViewModal({ deviceNumber, onClose }: Props) {
  const toast = useToast();
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const [status, setStatus] = useState<
    'initializing' | 'waiting' | 'connecting' | 'connected' | 'stopped' | 'error'
  >('initializing');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const pcRef = useRef<RTCPeerConnection | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function initSession() {
      try {
        setStatus('initializing');
        const { sessionId } = await startRemoteSession(deviceNumber);
        if (cancelled) return;

        sessionIdRef.current = sessionId;
        setStatus('waiting');

        const pc = new RTCPeerConnection({ iceServers: [] });
        pcRef.current = pc;

        pc.ontrack = (event) => {
          if (videoRef.current && event.streams[0]) {
            videoRef.current.srcObject = event.streams[0];
            setStatus('connected');
          }
        };

        pc.onicecandidate = (event) => {
          if (event.candidate && event.candidate.candidate && sessionIdRef.current) {
            void sendRemoteSignal(deviceNumber, sessionIdRef.current, {
              type: 'iceCandidate',
              candidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid ?? undefined,
                sdpMLineIndex: event.candidate.sdpMLineIndex ?? undefined,
              },
            }).catch(() => undefined);
          }
        };

        pc.oniceconnectionstatechange = () => {
          if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected') {
            setStatus((prev) => (prev === 'connected' ? 'connecting' : prev));
          }
        };

        pc.onconnectionstatechange = () => {
          if (pc.connectionState === 'connected') {
            setStatus('connected');
          } else if (pc.connectionState === 'failed') {
            setStatus('error');
            setErrorMessage('WebRTC connection failed');
          }
        };

        // Signaling poll loop
        pollTimerRef.current = setInterval(() => {
          if (cancelled || !sessionIdRef.current) return;
          const currentSessionId = sessionIdRef.current;

          void getRemoteSignals(deviceNumber, currentSessionId)
            .then(async (signals: RemoteSignal[]) => {
              for (const sig of signals) {
                if (sig.type === 'offer' && sig.sdp && pcRef.current) {
                  setStatus('connecting');
                  await pcRef.current.setRemoteDescription(
                    new RTCSessionDescription({ type: 'offer', sdp: sig.sdp })
                  );
                  const answer = await pcRef.current.createAnswer();
                  await pcRef.current.setLocalDescription(answer);
                  await sendRemoteSignal(deviceNumber, currentSessionId, {
                    type: 'answer',
                    sdp: answer.sdp,
                  });
                } else if (sig.type === 'iceCandidate' && sig.candidate && pcRef.current) {
                  await pcRef.current.addIceCandidate(
                    new RTCIceCandidate(sig.candidate)
                  );
                } else if (sig.type === 'stop') {
                  setStatus('stopped');
                }
              }
            })
            .catch(() => undefined);
        }, 500);
      } catch (e) {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : 'Failed to start remote viewing session';
        setStatus('error');
        setErrorMessage(msg);
        toast.push('err', 'Remote view error', msg);
      }
    }

    void initSession();

    return () => {
      cancelled = true;
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
      if (sessionIdRef.current) {
        const sid = sessionIdRef.current;
        sessionIdRef.current = null;
        void stopRemoteSession(deviceNumber, sid).catch(() => undefined);
      }
      if (pcRef.current) {
        pcRef.current.close();
        pcRef.current = null;
      }
      if (videoRef.current) {
        videoRef.current.srcObject = null;
      }
    };
  }, [deviceNumber, toast]);

  function handleStop() {
    if (sessionIdRef.current) {
      const sid = sessionIdRef.current;
      sessionIdRef.current = null;
      void stopRemoteSession(deviceNumber, sid).catch(() => undefined);
    }
    if (pcRef.current) {
      pcRef.current.close();
      pcRef.current = null;
    }
    onClose();
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal" style={{ maxWidth: '800px', width: '90%' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h3>Live Screen View — Device {deviceNumber}</h3>
          <span className={`badge status-${status === 'connected' ? 'active' : status === 'error' ? 'failed' : 'pending'}`}>
            {status.toUpperCase()}
          </span>
        </div>

        {errorMessage && (
          <div style={{ color: 'var(--color-danger, #d9534f)', marginBottom: '1rem' }}>
            {errorMessage}
          </div>
        )}

        <div
          style={{
            background: '#000',
            borderRadius: '8px',
            overflow: 'hidden',
            minHeight: '360px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            position: 'relative',
          }}
        >
          {status !== 'connected' && (
            <div style={{ color: '#fff', textAlign: 'center', padding: '2rem' }}>
              <p>{status === 'initializing' && 'Requesting screen viewing session...'}</p>
              <p>{status === 'waiting' && 'Waiting for device agent to connect...'}</p>
              <p>{status === 'connecting' && 'Establishing WebRTC stream...'}</p>
              <p>{status === 'stopped' && 'Session stopped.'}</p>
              <p>{status === 'error' && (errorMessage || 'Error starting session')}</p>
            </div>
          )}

          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            style={{
              width: '100%',
              maxHeight: '600px',
              objectFit: 'contain',
              display: status === 'connected' ? 'block' : 'none',
            }}
          />
        </div>

        <div className="modal-actions" style={{ marginTop: '1rem' }}>
          <button className="btn btn-danger" onClick={handleStop}>
            Stop Session
          </button>
        </div>
      </div>
    </div>
  );
}
