import { useEffect, useRef, useState } from 'react';
import { searchDevices, type DeviceView } from '../api/devices';
import { scanApps, type AppInfo } from '../api/deviceApps';
import { listApplications, saveAndroidApplication } from '../api/applications';
import { useToast } from '../ui/toast';

interface FetchFromDevicesModalProps {
  onClose: () => void;
  onDone: () => void;
}

export function FetchFromDevicesModal({ onClose, onDone }: FetchFromDevicesModalProps) {
  const toast = useToast();
  const [devices, setDevices] = useState<DeviceView[] | null>(null);
  const [loadingDevices, setLoadingDevices] = useState(true);
  const [deviceError, setDeviceError] = useState<string | null>(null);

  const [targetMode, setTargetMode] = useState<'all' | 'selected'>('all');
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<number[]>([]);
  const [deviceQuery, setDeviceQuery] = useState('');

  const [progressText, setProgressText] = useState('');
  const [status, setStatus] = useState<'idle' | 'running' | 'done'>('idle');

  const [scannedCount, setScannedCount] = useState(0);
  const [addedCount, setAddedCount] = useState(0);
  const [errors, setErrors] = useState<string[]>([]);

  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    let cancelled = false;
    searchDevices({ pageSize: 1000 })
      .then((res) => {
        if (cancelled) return;
        const items = res.devices?.items ?? [];
        setDevices(items);
        setSelectedDeviceIds(items.map((d) => d.id));
      })
      .catch((e) => {
        if (cancelled) return;
        setDeviceError(e instanceof Error ? e.message : 'Failed to load devices list');
      })
      .finally(() => {
        if (!cancelled) setLoadingDevices(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const filteredDevices = (devices ?? []).filter((d) => {
    const q = deviceQuery.trim().toLowerCase();
    if (!q) return true;
    return (
      d.number.toLowerCase().includes(q) ||
      (d.description ?? '').toLowerCase().includes(q) ||
      (d.serial ?? '').toLowerCase().includes(q)
    );
  });

  function toggleDevice(id: number) {
    setSelectedDeviceIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  function toggleSelectAll() {
    if (!devices) return;
    if (selectedDeviceIds.length === devices.length) {
      setSelectedDeviceIds([]);
    } else {
      setSelectedDeviceIds(devices.map((d) => d.id));
    }
  }

  async function startFetch() {
    if (!devices || devices.length === 0) return;
    const targetDevices =
      targetMode === 'all'
        ? devices
        : devices.filter((d) => selectedDeviceIds.includes(d.id));

    if (targetDevices.length === 0) {
      toast.push('err', 'No devices selected', 'Select at least one device to fetch applications.');
      return;
    }

    setStatus('running');
    setErrors([]);
    setScannedCount(0);
    setAddedCount(0);

    const controller = new AbortController();
    abortRef.current = controller;

    const scannedAppsMap = new Map<string, AppInfo>();
    const errList: string[] = [];

    for (let i = 0; i < targetDevices.length; i++) {
      if (controller.signal.aborted) break;
      const dev = targetDevices[i];
      setProgressText(`Scanning device ${i + 1} of ${targetDevices.length}: ${dev.number}…`);

      try {
        const apps = await scanApps(dev.id, controller.signal);
        for (const app of apps) {
          if (!app.pkg) continue;
          const existingScanned = scannedAppsMap.get(app.pkg);
          if (!existingScanned) {
            scannedAppsMap.set(app.pkg, app);
          } else {
            // Update metadata if fuller
            if (!existingScanned.label && app.label) existingScanned.label = app.label;
            if (!existingScanned.versionName && app.versionName) existingScanned.versionName = app.versionName;
            if ((app.versionCode ?? 0) > (existingScanned.versionCode ?? 0)) {
              existingScanned.versionCode = app.versionCode;
              if (app.versionName) existingScanned.versionName = app.versionName;
            }
          }
        }
      } catch (e) {
        if (controller.signal.aborted) break;
        const msg = e instanceof Error ? e.message : 'Unknown error';
        errList.push(`Device ${dev.number}: ${msg}`);
      }
    }

    if (controller.signal.aborted) {
      setStatus('idle');
      return;
    }

    const uniqueScanned = Array.from(scannedAppsMap.values());
    setScannedCount(uniqueScanned.length);

    setProgressText('Comparing scanned applications with Library…');
    let newAdded = 0;

    try {
      const existingApps = await listApplications();
      const existingPkgs = new Set(existingApps.map((a) => a.pkg.toLowerCase()));

      const missingApps = uniqueScanned.filter(
        (a) => a.pkg && !existingPkgs.has(a.pkg.toLowerCase()),
      );

      for (let i = 0; i < missingApps.length; i++) {
        if (controller.signal.aborted) break;
        const app = missingApps[i];
        setProgressText(`Adding missing application ${i + 1} of ${missingApps.length}: ${app.label || app.pkg}…`);

        try {
          await saveAndroidApplication({
            name: app.label || app.pkg,
            pkg: app.pkg,
            version: app.versionName || (app.versionCode ? String(app.versionCode) : '1.0'),
            versionCode: app.versionCode || 1,
            type: 'app',
            system: app.system ?? false,
          });
          newAdded++;
          setAddedCount(newAdded);
        } catch (e) {
          // If save fails, check if already exists or log error
          const msg = e instanceof Error ? e.message : 'Save failed';
          errList.push(`App ${app.pkg}: ${msg}`);
        }
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to compare with Library';
      errList.push(`Library check: ${msg}`);
    }

    setErrors(errList);
    setStatus('done');
    setProgressText('');
  }

  function handleClose() {
    if (abortRef.current) {
      abortRef.current.abort();
    }
    if (status === 'done') {
      onDone();
    }
    onClose();
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" onClick={handleClose}>
      <div className="modal" style={{ maxWidth: 600 }} onClick={(e) => e.stopPropagation()}>
        <h3>Fetch applications from devices</h3>

        {loadingDevices && (
          <div className="empty">
            <span className="spin" /> Loading devices list…
          </div>
        )}

        {deviceError && <div className="banner banner-alert">{deviceError}</div>}

        {!loadingDevices && !deviceError && status === 'idle' && (
          <>
            <p className="note">
              Scan installed applications on managed devices and automatically add any missing
              applications to your Library.
            </p>

            <div style={{ display: 'flex', gap: 16, marginBottom: 14 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="targetMode"
                  checked={targetMode === 'all'}
                  onChange={() => setTargetMode('all')}
                />
                <span>All devices ({devices?.length ?? 0})</span>
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input
                  type="radio"
                  name="targetMode"
                  checked={targetMode === 'selected'}
                  onChange={() => setTargetMode('selected')}
                />
                <span>Selected devices ({selectedDeviceIds.length})</span>
              </label>
            </div>

            {targetMode === 'selected' && (
              <div style={{ border: '1px solid var(--border-color, #ccc)', borderRadius: 6, padding: 10, maxHeight: 220, overflowY: 'auto' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                  <input
                    className="input"
                    style={{ width: 180, padding: '4px 8px', fontSize: 13 }}
                    placeholder="Filter devices…"
                    value={deviceQuery}
                    onChange={(e) => setDeviceQuery(e.target.value)}
                  />
                  <button type="button" className="btn btn-sm" onClick={toggleSelectAll}>
                    {selectedDeviceIds.length === (devices?.length ?? 0) ? 'Deselect all' : 'Select all'}
                  </button>
                </div>
                {filteredDevices.length === 0 ? (
                  <p className="muted" style={{ margin: 0 }}>No matching devices</p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {filteredDevices.map((d) => (
                      <label key={d.id} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: 'pointer' }}>
                        <input
                          type="checkbox"
                          checked={selectedDeviceIds.includes(d.id)}
                          onChange={() => toggleDevice(d.id)}
                        />
                        <span className="mono">{d.number}</span>
                        {d.description && <span className="muted">({d.description})</span>}
                      </label>
                    ))}
                  </div>
                )}
              </div>
            )}

            <div className="modal-actions" style={{ marginTop: 18 }}>
              <button className="btn" onClick={handleClose}>
                Cancel
              </button>
              <button
                className="btn btn-primary"
                disabled={
                  (targetMode === 'selected' && selectedDeviceIds.length === 0) ||
                  !devices ||
                  devices.length === 0
                }
                onClick={() => void startFetch()}
              >
                Start Fetch
              </button>
            </div>
          </>
        )}

        {status === 'running' && (
          <div style={{ padding: '20px 0', textAlign: 'center' }}>
            <span className="spin" style={{ display: 'inline-block', marginBottom: 12 }} />
            <p style={{ fontWeight: 500, margin: '0 0 8px 0' }}>{progressText}</p>
            <p className="muted" style={{ fontSize: 13, margin: 0 }}>
              Please keep this window open while applications are fetched from the devices.
            </p>
            <div className="modal-actions" style={{ marginTop: 20 }}>
              <button
                className="btn btn-danger"
                onClick={() => {
                  if (abortRef.current) abortRef.current.abort();
                  setStatus('idle');
                }}
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {status === 'done' && (
          <div>
            <div className="banner banner-success" style={{ marginBottom: 14 }}>
              Scan completed!
            </div>
            <p style={{ margin: '0 0 8px 0' }}>
              <strong>Summary:</strong>
            </p>
            <ul style={{ margin: '0 0 14px 20px', padding: 0 }}>
              <li>Unique installed applications scanned: <strong>{scannedCount}</strong></li>
              <li>New applications created in Library: <strong>{addedCount}</strong></li>
            </ul>

            {errors.length > 0 && (
              <div style={{ marginBottom: 14 }}>
                <p className="muted" style={{ margin: '0 0 4px 0', fontSize: 13 }}>
                  Warnings / Errors ({errors.length}):
                </p>
                <div
                  className="mono"
                  style={{
                    fontSize: 12,
                    background: 'var(--bg-muted, #f5f5f5)',
                    padding: 8,
                    borderRadius: 4,
                    maxHeight: 120,
                    overflowY: 'auto',
                  }}
                >
                  {errors.map((err, idx) => (
                    <div key={idx}>{err}</div>
                  ))}
                </div>
              </div>
            )}

            <div className="modal-actions">
              <button className="btn btn-primary" onClick={handleClose}>
                Done
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
