package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.AssembleChunkRequest;
import com.hmdm.rest.json.FileUploadResult;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FilesResourceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testExtractApkPartsWithStoredAndDeflatedEntries() throws IOException {
        File zipFile = temporaryFolder.newFile("test_bundle.xapk");

        byte[] baseApkBytes = "dummy base apk content".getBytes("UTF-8");
        byte[] configApkBytes = "dummy config apk content".getBytes("UTF-8");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            // Write STORED entry for base.apk
            ZipEntry storedEntry = new ZipEntry("base.apk");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(baseApkBytes.length);
            storedEntry.setCompressedSize(baseApkBytes.length);
            CRC32 crc32 = new CRC32();
            crc32.update(baseApkBytes);
            storedEntry.setCrc(crc32.getValue());
            zos.putNextEntry(storedEntry);
            zos.write(baseApkBytes);
            zos.closeEntry();

            // Write DEFLATED entry for config.apk
            ZipEntry deflatedEntry = new ZipEntry("config.apk");
            deflatedEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(deflatedEntry);
            zos.write(configApkBytes);
            zos.closeEntry();

            // Write a non-apk entry that should be ignored
            ZipEntry nonApkEntry = new ZipEntry("icon.png");
            nonApkEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(nonApkEntry);
            zos.write("icon image bytes".getBytes("UTF-8"));
            zos.closeEntry();
        }

        FilesResource filesResource = new FilesResource();
        List<File> parts = filesResource.extractApkParts(zipFile);

        try {
            Assert.assertEquals(2, parts.size());
            for (File part : parts) {
                Assert.assertTrue(part.exists());
                Assert.assertTrue(part.length() > 0);
            }
        } finally {
            for (File part : parts) {
                part.delete();
            }
        }
    }

    @Test
    public void testExtractApkPartsUniversalApk() throws IOException {
        File zipFile = temporaryFolder.newFile("bundletool.apks");

        byte[] universalBytes = "universal apk content".getBytes("UTF-8");
        byte[] splitBytes = "split apk content".getBytes("UTF-8");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry universalEntry = new ZipEntry("universal.apk");
            zos.putNextEntry(universalEntry);
            zos.write(universalBytes);
            zos.closeEntry();

            ZipEntry splitEntry = new ZipEntry("base-master.apk");
            zos.putNextEntry(splitEntry);
            zos.write(splitBytes);
            zos.closeEntry();
        }

        FilesResource filesResource = new FilesResource();
        List<File> parts = filesResource.extractApkParts(zipFile);

        try {
            Assert.assertEquals(1, parts.size());
            File universalFile = parts.get(0);
            Assert.assertTrue(universalFile.exists());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (java.io.InputStream is = new java.io.FileInputStream(universalFile)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
            }
            Assert.assertEquals("universal apk content", new String(baos.toByteArray(), "UTF-8"));
        } finally {
            for (File part : parts) {
                part.delete();
            }
        }
    }

    @Test
    public void testChunkedUploadAndAssemblySuccess() throws IOException {
        User user = new User();
        UserRole role = new UserRole();
        role.setSuperAdmin(true);
        user.setUserRole(role);
        user.setCustomerId(1);
        SecurityContext.init(user);

        try {
            FilesResource filesResource = new FilesResource();
            String uploadId = "test-session-success";

            byte[] chunk0 = "Chunk 0 data - ".getBytes("UTF-8");
            byte[] chunk1 = "Chunk 1 data - ".getBytes("UTF-8");
            byte[] chunk2 = "Chunk 2 data.".getBytes("UTF-8");

            Response r0 = filesResource.uploadChunk(new ByteArrayInputStream(chunk0), uploadId, 0, 3);
            Assert.assertEquals(Response.OK().getStatus(), r0.getStatus());

            Response r1 = filesResource.uploadChunk(new ByteArrayInputStream(chunk1), uploadId, 1, 3);
            Assert.assertEquals(Response.OK().getStatus(), r1.getStatus());

            Response r2 = filesResource.uploadChunk(new ByteArrayInputStream(chunk2), uploadId, 2, 3);
            Assert.assertEquals(Response.OK().getStatus(), r2.getStatus());

            AssembleChunkRequest req = new AssembleChunkRequest();
            req.setUploadId(uploadId);
            req.setFileName("assembled.txt");
            req.setParseFile(false);
            req.setBundle(false);

            Response assembleResp = filesResource.assembleChunks(req);
            Assert.assertEquals(Response.OK().getStatus(), assembleResp.getStatus());

            FileUploadResult result = (FileUploadResult) assembleResp.getData();
            Assert.assertNotNull(result);
            Assert.assertNotNull(result.getServerPath());

            File assembledFile = new File(result.getServerPath());
            Assert.assertTrue(assembledFile.exists());

            byte[] fileBytes = Files.readAllBytes(assembledFile.toPath());
            String expectedContent = "Chunk 0 data - Chunk 1 data - Chunk 2 data.";
            Assert.assertEquals(expectedContent, new String(fileBytes, "UTF-8"));

            assembledFile.delete();
            File chunkDir = new File(System.getProperty("java.io.tmpdir"), "hmdm_chunks/" + uploadId);
            Assert.assertFalse(chunkDir.exists());
        } finally {
            SecurityContext.release();
        }
    }

    @Test
    public void testChunkedUploadAssemblyMissingChunkFailure() throws IOException {
        User user = new User();
        UserRole role = new UserRole();
        role.setSuperAdmin(true);
        user.setUserRole(role);
        user.setCustomerId(1);
        SecurityContext.init(user);

        try {
            FilesResource filesResource = new FilesResource();
            String uploadId = "test-session-missing";

            byte[] chunk0 = "Chunk 0 data".getBytes("UTF-8");
            byte[] chunk2 = "Chunk 2 data".getBytes("UTF-8");

            filesResource.uploadChunk(new ByteArrayInputStream(chunk0), uploadId, 0, 3);
            filesResource.uploadChunk(new ByteArrayInputStream(chunk2), uploadId, 2, 3);

            AssembleChunkRequest req = new AssembleChunkRequest();
            req.setUploadId(uploadId);
            req.setFileName("missing.txt");
            req.setParseFile(false);
            req.setBundle(false);

            Response assembleResp = filesResource.assembleChunks(req);
            Assert.assertEquals(Response.ERROR().getStatus(), assembleResp.getStatus());
            Assert.assertEquals("error.chunk.missing", assembleResp.getMessage());
        } finally {
            SecurityContext.release();
        }
    }

    @Test
    public void testChunkedUploadCleanupOnAssembleError() throws IOException {
        User user = new User();
        UserRole role = new UserRole();
        role.setSuperAdmin(true);
        user.setUserRole(role);
        user.setCustomerId(1);
        SecurityContext.init(user);

        try {
            FilesResource filesResource = new FilesResource();
            String uploadId = "test-session-cleanup";

            byte[] chunk0 = "Chunk 0 data".getBytes("UTF-8");
            filesResource.uploadChunk(new ByteArrayInputStream(chunk0), uploadId, 0, 1);

            File chunkDir = new File(System.getProperty("java.io.tmpdir"), "hmdm_chunks/" + uploadId);
            Assert.assertTrue(chunkDir.exists());

            AssembleChunkRequest req = new AssembleChunkRequest();
            req.setUploadId(uploadId);
            req.setFileName("test.txt");
            req.setParseFile(false);
            req.setBundle(false);

            Response assembleResp = filesResource.assembleChunks(req);
            Assert.assertEquals(Response.OK().getStatus(), assembleResp.getStatus());
            Assert.assertFalse(chunkDir.exists());
        } finally {
            SecurityContext.release();
        }
    }
}
