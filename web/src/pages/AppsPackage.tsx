import { useState } from 'react';
import { listApplications, saveAndroidApplication } from '../api/applications';
import { Button, Card, CardHeader, Input, useToast } from '../ui';

/**
 * "Add by package name": many system or pre-installed apps are not in the library, but a configuration can still
 * install or remove them. This records the package name in the library (fork feature) so it becomes assignable.
 */
export function AppsPackage({ onLibraryChanged }: { onLibraryChanged: () => void }) {
  const toast = useToast();
  const [pkg, setPkg] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  async function save() {
    const trimmedPkg = pkg.trim();
    if (!trimmedPkg) {
      toast.push('err', 'Package name required', 'Enter a package name (e.g. com.android.chrome).');
      return;
    }
    setBusy(true);
    setSaved(false);
    try {
      await saveAndroidApplication({
        name: name.trim() || trimmedPkg,
        pkg: trimmedPkg,
        version: '1.0',
        versionCode: 1,
        type: 'app',
      });
      setSaved(true);
      onLibraryChanged();
      toast.push('ok', 'Added to library', `${name.trim() || trimmedPkg} is now assignable to a configuration.`);
      setPkg('');
      setName('');
    } catch (e) {
      // Most likely it is already in the library (same package): reuse it instead of failing.
      const existing = (await listApplications(trimmedPkg).catch(() => [])).find((a) => a.pkg === trimmedPkg);
      if (existing?.id) {
        setSaved(true);
        onLibraryChanged();
        toast.push('ok', 'Already in library', 'This app is already in your library — assign it to a configuration.');
      } else {
        toast.push('err', 'Could not add to library', e instanceof Error ? e.message : 'The server rejected the save.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card as="section" padding="rows">
      <CardHeader
        title="Add by package name"
        subtitle="Record a pre-installed system app in the library, then install or remove it from a configuration."
      />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 560 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontWeight: 600, fontSize: 13 }}>Package name *</span>
          <Input
            mono
            value={pkg}
            onChange={(e) => setPkg(e.target.value)}
            placeholder="e.g. com.android.chrome"
            autoFocus
          />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontWeight: 600, fontSize: 13 }}>Display name</span>
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Google Chrome (optional)"
          />
        </label>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          <Button variant="primary" onClick={() => void save()} disabled={busy || !pkg.trim()}>
            {busy ? 'Saving…' : 'Add to library'}
          </Button>
          {saved ? <span style={{ color: "var(--v2-text-2, #6b7683)" }}>App is in the library — assignable to a configuration.</span> : null}
        </div>
      </div>
    </Card>
  );
}
