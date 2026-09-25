import { apiClient } from './client';

// Applications library — see com.hmdm.rest.resource.ApplicationResource (@Path /private/applications).
// All responses use the {status,data} envelope (apiClient unwraps to data).

export interface Application {
  id: number;
  name: string;
  pkg: string;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  icon?: string;
  iconId?: number;
  /** "app" | "web" */
  type?: string;
  system?: boolean;
  latestVersion?: number;
  runAfterInstall?: boolean;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]` (server stores it as text);
   *  present only for multi-part bundles. Use JSON.parse to read, JSON.stringify to send. */
  parts?: string;
}

export interface ApplicationVersion {
  id: number;
  applicationId: number;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  arch?: string | null;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]`; present only for bundle versions. */
  parts?: string;
}

// ApplicationConfigurationLink — the app↔configuration matrix.
// action: 0 = hide, 1 = install, 2 = remove.
export interface AppConfigLink {
  id?: number;
  configurationId: number;
  configurationName?: string;
  applicationId: number;
  applicationName?: string;
  action: number;
  showIcon?: boolean;
  remove?: boolean;
  notify?: boolean;
}

export interface LinkConfigurationsToAppRequest {
  applicationId: number;
  configurations: AppConfigLink[];
}

// Library apps fall into three buckets. "uploaded" = the APKs/apps an admin added
// (non-system); "system" = the large pre-seeded OS app list; "web" = web-app shortcuts.
export type AppCategory = 'uploaded' | 'system' | 'web';

export function appCategory(a: Application): AppCategory {
  if ((a.type ?? 'app') === 'web') return 'web';
  if (a.system) return 'system';
  return 'uploaded';
}

/** Create (id absent) or update an Android application in the Library. */
export async function saveAndroidApplication(app: Partial<Application>): Promise<Application> {
  return apiClient.put<Application>('/private/applications/android', app);
}

export async function listApplications(value?: string): Promise<Application[]> {
  const v = value?.trim();
  const path = v
    ? `/private/applications/search/${encodeURIComponent(v)}`
    : '/private/applications/search';
  return apiClient.get<Application[]>(path);
}

export async function getVersions(appId: number): Promise<ApplicationVersion[]> {
  return apiClient.get<ApplicationVersion[]>(`/private/applications/${appId}/versions`);
}

export async function getAppConfigLinks(appId: number): Promise<AppConfigLink[]> {
  return apiClient.get<AppConfigLink[]>(`/private/applications/configurations/${appId}`);
}

export async function updateAppConfigLinks(
  req: LinkConfigurationsToAppRequest,
): Promise<void> {
  await apiClient.post('/private/applications/configurations', req);
}

// --- APK upload (drop → analyze → host) -------------------------------------
// See FilesResource (@Path /private/web-ui-files):
//   POST /                 multipart "file" -> FileUploadResult (parses the APK)
//   POST /update           commit the temp upload -> served file with a public url

/** Parsed APK metadata from the server's APKFileAnalyzer. */
export interface ApkFileDetails {
  pkg: string;
  name?: string;
  version?: string;
  versionCode?: number;
  arch?: string | null;
}

export interface FileUploadResult {
  name?: string;
  /** Absolute temp path on the server; pass back to commit. */
  serverPath: string;
  fileDetails?: ApkFileDetails;
  exists?: boolean;
  complete?: boolean;
}

export interface UploadedFileView {
  id?: number;
  /** Public, agent-reachable URL once committed. */
  url?: string;
  filePath?: string;
  fileName?: string;
}

/** Chunk size for uploading large files in smaller parts (10MB). */
const CHUNK_SIZE = 10 * 1024 * 1024;

export interface UploadChunkOptions {
  onProgress?: (percent: number) => void;
}

/**
 * Helper to upload a file in chunks to avoid request size limits (e.g. Cloudflare 100MB limit).
 * Uploads chunks sequentially, then requests assembly on the server.
 */
export async function uploadFileInChunks<T>(
  file: File,
  isBundle: boolean,
  parseFile = true,
  options?: UploadChunkOptions,
): Promise<T> {
  const totalSize = file.size;
  const totalChunks = Math.ceil(totalSize / CHUNK_SIZE);
  const uploadId = `${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;

  for (let i = 0; i < totalChunks; i++) {
    const start = i * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, totalSize);
    const chunkBlob = file.slice(start, end);

    const form = new FormData();
    form.append('uploadId', uploadId);
    form.append('chunkIndex', String(i));
    form.append('totalChunks', String(totalChunks));
    form.append('file', chunkBlob, file.name);

    await apiClient.postForm<void>('/private/web-ui-files/chunk', form);

    if (options?.onProgress) {
      const progress = Math.round(((i + 1) / totalChunks) * 100);
      options.onProgress(progress);
    }
  }

  // Request server to reassemble chunks
  return apiClient.post<T>('/private/web-ui-files/chunk/assemble', {
    uploadId,
    fileName: file.name,
    isBundle,
    parseFile,
  });
}

/** Upload an APK; the server parses it and returns its package/version details. Uses chunked upload. */
export async function uploadApk(file: File, options?: UploadChunkOptions): Promise<FileUploadResult> {
  return uploadFileInChunks<FileUploadResult>(file, false, true, options);
}

// --- Split-APK bundle upload (.xapk/.apks/.apkm/.zip) ------------------------
// The server unpacks the bundle, hosts each split, and returns the parts to
// install together in one session. See the /bundle endpoint on FilesResource.

export interface BundleUploadResult {
  name: string;
  packageName: string;
  version?: string;
  versionCode: number;
  parts: { url: string; sha256: string; name: string }[];
}

/** Upload a split-APK bundle; the server unpacks it and returns the hosted parts. Uses chunked upload. */
export async function uploadBundle(file: File, options?: UploadChunkOptions): Promise<BundleUploadResult> {
  return uploadFileInChunks<BundleUploadResult>(file, true, false, options);
}

/** Commit a just-uploaded temp file into the served files area; returns its url. */
export async function commitUpload(serverPath: string): Promise<UploadedFileView> {
  return apiClient.post<UploadedFileView>('/private/web-ui-files/update', {
    tmpPath: serverPath,
    fileName: '',
    filePath: '',
    external: false,
  });
}

/** Delete an application from the Library by ID. */
export async function deleteApplication(id: number): Promise<void> {
  await apiClient.del(`/private/applications/${id}`);
}

/** Upload an icon image file (must be square). Returns the uploaded file record containing fileId and filePath. */
export async function uploadIconFile(file: File): Promise<UploadedFileView> {
  const form = new FormData();
  form.append('file', file, file.name);
  return apiClient.postForm<UploadedFileView>('/private/icon-files', form);
}

export interface IconRecord {
  id?: number;
  name: string;
  fileId: number;
}

/** Create or update an Icon record on the server. Returns the saved Icon containing its id. */
export async function createIcon(icon: IconRecord): Promise<IconRecord> {
  return apiClient.put<IconRecord>('/private/icons', icon);
}
