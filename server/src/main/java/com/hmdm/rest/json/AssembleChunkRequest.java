package com.hmdm.rest.json;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "Request to reassemble chunked file uploads")
public class AssembleChunkRequest {

    @ApiModelProperty("Upload session identifier")
    private String uploadId;

    @ApiModelProperty("Original file name")
    private String fileName;

    @ApiModelProperty("Whether the file is a split APK bundle")
    private boolean isBundle;

    @ApiModelProperty("Whether to parse APK metadata")
    private boolean parseFile = true;

    public AssembleChunkRequest() {
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public boolean isBundle() {
        return isBundle;
    }

    public void setBundle(boolean bundle) {
        isBundle = bundle;
    }

    public boolean isParseFile() {
        return parseFile;
    }

    public void setParseFile(boolean parseFile) {
        this.parseFile = parseFile;
    }
}
