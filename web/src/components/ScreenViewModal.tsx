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

interface LogEntry {
  id: number;
  time: string;
  text: string;
  level: 'info' | 'warn' | 'error';
}

export function ScreenViewModal({ deviceNumber, onClose }: Props) {
  const toast = useToast();
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const logContainerRef = useRef<HTMLDivElement | null>(null);

  const [status, setStatus] = useState<
    'initializing' | 'waiting' | 'connecting' | 'connected' | 'stopped' | 'error'
  >('initializing');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [showLogs, setShowLogs] = useState<boolean>(true);

  const pcRef = useRef<RTCPeerConnection | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const logIdRef = useRef<number>(0);

  const addLog = (text: string, level: 'info' | 'warn' | 'error' = 'info') => {
    const time = new Date().toLocaleTimeString();
    const newEntry: LogEntry = { id: ++logIdRef.current, time, text, level };
    setLogs((prev) => [...prev, newEntry]);
  };

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs]);

  useEffect(() => {
    let cancelled = false;

    async function initSession() {
      try {
        setStatus('initializing');
        addLog(`Requesting remote viewing session for device ${deviceNumber}...`);
        const { sessionId } = await startRemoteSession(deviceNumber);
        if (cancelled) return;

        sessionIdRef.current = sessionId;
        setStatus('waiting');
        addLog(`Session created: ${sessionId}. Waiting for device agent...`);

        const pc = new RTCPeerConnection({ iceServers: [] });
        pcRef.current = pc;
        addLog('Initialized RTCPeerConnection.');

        pc.ontrack = (event) => {
          addLog('Received remote video track from device agent.');
          if (videoRef.current && event.streams[0]) {
            videoRef.current.srcObject = event.streams[0];
            setStatus('connected');
            addLog('Connected to device agent stream.', 'info');
          }
        };

        pc.onicecandidate = (event) => {
          if (event.candidate && event.candidate.candidate && sessionIdRef.current) {
            addLog(`Gathered local ICE candidate: ${event.candidate.sdpMid ?? ''}`);
            void sendRemoteSignal(deviceNumber, sessionIdRef.current, {
              type: 'iceCandidate',
              candidate: {
                candidate: event.candidate.candidate,
                sdpMid: event.candidate.sdpMid ?? undefined,
                sdpMLineIndex: event.candidate.sdpMLineIndex ?? undefined,
              },
            }).catch((err) => {
              addLog(`Failed to send ICE candidate: ${err instanceof Error ? err.message : String(err)}`, 'warn');
            });
          }
        };

        pc.oniceconnectionstatechange = () => {
          const state = pc.iceConnectionState;
          addLog(`ICE connection state changed to: ${state}`, state === 'failed' ? 'error' : 'info');
          if (state === 'failed' || state === 'disconnected') {
            setStatus((prev) => (prev === 'connected' ? 'connecting' : prev));
          }
        };

        pc.onconnectionstatechange = () => {
          const state = pc.connectionState;
          addLog(`Peer connection state changed to: ${state}`, state === 'failed' ? 'error' : 'info');
          if (state === 'connected') {
            setStatus('connected');
          } else if (state === 'failed') {
            setStatus('error');
            setErrorMessage('WebRTC connection failed');
          }
        };

        // Signaling poll loop
        addLog('Starting signal polling loop...');
        pollTimerRef.current = setInterval(() => {
          if (cancelled || !sessionIdRef.current) return;
          const currentSessionId = sessionIdRef.current;

          void getRemoteSignals(deviceNumber, currentSessionId)
            .then(async (signals: RemoteSignal[]) => {
              for (const sig of signals) {
                addLog(`Received signal from agent: ${sig.type}`);
                if (sig.type === 'offer' && sig.sdp && pcRef.current) {
                  setStatus('connecting');
                  addLog('Applying remote SDP offer...');
                  await pcRef.current.setRemoteDescription(
                    new RTCSessionDescription({ type: 'offer', sdp: sig.sdp })
                  );
                  addLog('Creating SDP answer...');
                  const answer = await pcRef.current.createAnswer();
                  await pcRef.current.setLocalDescription(answer);
                  addLog('Sending SDP answer to device agent...');
                  await sendRemoteSignal(deviceNumber, currentSessionId, {
                    type: 'answer',
                    sdp: answer.sdp,
                  });
                  addLog('SDP answer sent successfully.');
                } else if (sig.type === 'iceCandidate' && sig.candidate && pcRef.current) {
                  addLog(`Adding remote ICE candidate: ${sig.candidate.sdpMid ?? ''}`);
                  await pcRef.current.addIceCandidate(
                    new RTCIceCandidate(sig.candidate)
                  );
                } else if (sig.type === 'stop') {
                  addLog('Received stop signal from device agent.', 'warn');
                  setStatus('stopped');
                }
              }
            })
            .catch((err) => {
              addLog(`Error polling remote signals: ${err instanceof Error ? err.message : String(err)}`, 'warn');
            });
        }, 500);
      } catch (e) {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : 'Failed to start remote viewing session';
        setStatus('error');
        setErrorMessage(msg);
        addLog(`Session error: ${msg}`, 'error');
        toast.push('err', 'Remote view error', msg);
      }
    }

    void initSession();

    return () => {
      cancelled = true;
      addLog('Cleaning up WebRTC session...', 'info');
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
    addLog('Stopping session by user action...', 'info');
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

        <div style={{ marginTop: '1rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
            <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Live Application Debug Logs</span>
            <div>
              <button
                type="button"
                className="btn btn-sm"
                style={{ marginRight: '0.5rem' }}
                onClick={() => setLogs([])}
              >
                Clear
              </button>
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setShowLogs(!showLogs)}
              >
                {showLogs ? 'Hide Logs' : 'Show Logs'}
              </button>
            </div>
          </div>

          {showLogs && (
            <div
              ref={logContainerRef}
              style={{
                background: '#1a1a1a',
                color: '#e0e0e0',
                fontFamily: 'monospace',
                fontSize: '0.8rem',
                padding: '0.5rem 0.75rem',
                borderRadius: '6px',
                maxHeight: '160px',
                overflowY: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {logs.length === 0 ? (
                <div style={{ color: '#777', fontStyle: 'italic' }}>No logs captured yet...</div>
              ) : (
                logs.map((item) => (
                  <div
                    key={item.id}
                    style={{
                      color:
                        item.level === 'error'
                          ? '#ff6b6b'
                          : item.level === 'warn'
                          ? '#ffd166'
                          : '#00e676',
                      lineHeight: '1.4',
                    }}
                  >
                    [{item.time}] {item.text}
                  </div>
                ))
              )}
            </div>
          )}
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
