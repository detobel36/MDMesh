import { apiClient } from './client';

export interface StartRemoteSessionResponse {
  sessionId: string;
}

export interface RemoteSignal {
  type: string;
  sdp?: string;
  candidate?: {
    candidate: string;
    sdpMid?: string;
    sdpMLineIndex?: number;
  };
  status?: string;
  message?: string;
}

/** Starts a remote screen viewing session for a device. */
export async function startRemoteSession(deviceId: string): Promise<StartRemoteSessionResponse> {
  return apiClient.post<StartRemoteSessionResponse>(`/private/agent/v1/devices/${encodeURIComponent(deviceId)}/remote/start`);
}

/** Sends an SDP answer or ICE candidate signal from browser to agent. */
export async function sendRemoteSignal(deviceId: string, sessionId: string, signal: RemoteSignal): Promise<void> {
  return apiClient.post<void>(
    `/private/agent/v1/devices/${encodeURIComponent(deviceId)}/remote/session/${encodeURIComponent(sessionId)}/signal`,
    signal
  );
}

/** Fetches pending SDP offer / ICE candidate signals sent by the agent. */
export async function getRemoteSignals(deviceId: string, sessionId: string): Promise<RemoteSignal[]> {
  const res = await apiClient.get<RemoteSignal[]>(
    `/private/agent/v1/devices/${encodeURIComponent(deviceId)}/remote/session/${encodeURIComponent(sessionId)}/signals`
  );
  return res || [];
}

/** Stops an active remote screen viewing session. */
export async function stopRemoteSession(deviceId: string, sessionId: string): Promise<void> {
  return apiClient.post<void>(
    `/private/agent/v1/devices/${encodeURIComponent(deviceId)}/remote/session/${encodeURIComponent(sessionId)}/stop`
  );
}
