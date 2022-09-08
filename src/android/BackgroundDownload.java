/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */
package org.apache.cordova.backgroundDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.SparseArray;

/**
 * Based on DownloadManager which is intended to be used for long-running HTTP downloads. Support of Android 2.3. (API 9) and later
 * http://developer.android.com/reference/android/app/DownloadManager.html TODO: concurrent downloads support
 */

public class BackgroundDownload extends CordovaPlugin {

    private static final String TAG = "BackgroundDownload";

    private static final int ERROR_CANCELED = Integer.MAX_VALUE;

    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 500;
    private static final int BUFFER_SIZE = 16777216; //16MB

    private static class PermissionsRequest {

        private final JSONArray rawArgs;
        private final CallbackContext callbackContext;

        private PermissionsRequest(JSONArray rawArgs, CallbackContext callbackContext) {
            this.rawArgs = rawArgs;
            this.callbackContext = callbackContext;
        }
    }

    private static class Download {

        private Uri targetFileUri;
        private Uri tempFileUri;
        private final String notificationTitle;
        private final List<Map<String, String>> headers;
        private final String uriMatcher;
        private final String uriString;
        private final CallbackContext callbackContext; // The callback context from which we were invoked.
        private long downloadId = DOWNLOAD_ID_UNDEFINED;
        private Timer timerProgressUpdate = null;
        private boolean isCanceled;

        public static Download create(JSONArray args, CallbackContext callbackContext) throws JSONException {
            String uriMatcher = null;
            if (args.length() > 2 && !"null".equals(args.getString(2))) {
                uriMatcher = args.getString(2);
            }

            String notificationTitle = "org.apache.cordova.backgroundDownload plugin";
            if (args.length() > 3 && !"null".equals(args.getString(3))) {
                notificationTitle = args.getString(3);
            }

            List<Map<String, String>> headers = new ArrayList<>();
            if (args.length() > 4 && !"null".equals(args.getString(4))) {
                JSONArray headersArray = args.getJSONArray(4);
                for (int i = 0; i < headersArray.length(); i++) {
                    String key = headersArray.getJSONObject(i).getString("Key");
                    String value = headersArray.getJSONObject(i).getString("Value");
                    Map<String, String> header = new HashMap<>();
                    header.put("Key", key);
                    header.put("Value", value);
                    headers.add(header);
                }
            }

            return new Download(args.get(0).toString(), args.get(1).toString(), notificationTitle, uriMatcher, headers,
                    callbackContext);
        }

        public Download(String uriString, String targetFileUri, String notificationTitle,
                        String uriMatcher, List<Map<String, String>> headers, CallbackContext callbackContext) {
            this.uriString = uriString;
            this.setTargetFileUri(targetFileUri);
            this.notificationTitle = notificationTitle;
            this.uriMatcher = uriMatcher;
            this.headers = headers;
            this.callbackContext = callbackContext;
        }

        public Uri getTargetFileUri() {
            return targetFileUri;
        }

        public void setTargetFileUri(String targetFileUri) {
            this.targetFileUri = Uri.parse(targetFileUri);
        }

        public String getUriString() {
            return uriString;
        }

        public String getNotificationTitle() {
            return this.notificationTitle;
        }

        public List<Map<String, String>> getHeaders() {
            return this.headers;
        }

        public String getUriMatcher() {
            return uriMatcher;
        }

        public Uri getTempFileUri() {
            return tempFileUri;
        }

        public void setTempFileUri(String tempFileUri) {
            this.tempFileUri = Uri.parse(tempFileUri);
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }

        public long getDownloadId() {
            return downloadId;
        }

        public void setDownloadId(long downloadId) {
            this.downloadId = downloadId;
        }

        public Timer getTimerProgressUpdate() {
            return timerProgressUpdate;
        }

        public void setTimerProgressUpdate(Timer TimerProgressUpdate) {
            this.timerProgressUpdate = TimerProgressUpdate;
        }

        public void cancel() {
            this.isCanceled = true;
        }

        public boolean isCanceled() {
            return this.isCanceled;
        }

        public void reportError(int errorCode) {
            String reasonMsg = getUserFriendlyReason(errorCode);
            if ("".equals(reasonMsg))
                reasonMsg = String.format(Locale.getDefault(), "Download operation failed with reason: %d", errorCode);

            reportError(reasonMsg);
        }

        public void reportError(String msg) {
            this.callbackContext.error(msg);
        }
    }

    private SparseArray<PermissionsRequest> permissionRequests;

    private final HashMap<String, Download> activeDownloads = new HashMap<>();

    private DownloadManager getDownloadManager() {
        return (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        permissionRequests = new SparseArray<>();
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        try {
            if (action.equals("startAsync")) {
                cordova.getThreadPool().execute(() -> {
                    try {
                        startAsync(args, callbackContext);
                    } catch (JSONException ex) {
                        callbackContext.error(ex.getMessage());
                    }
                });

                return true;
            }
            if (action.equals("stop")) {
                stop(args, callbackContext);
                return true;
            }
            return false; // invalid action
        } catch (Exception ex) {
            callbackContext.error(ex.getMessage());
        }
        return true;
    }

    private void startAsync(JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (!checkPermissions(args, callbackContext)) {
            return;
        }

        Download curDownload = Download.create(args, callbackContext);
        // set temp file uri
        curDownload.setTempFileUri(Uri.fromFile(new File(cordova.getContext().getExternalFilesDir("Downloads").getPath(),
                curDownload.targetFileUri.getLastPathSegment() + "." + System.currentTimeMillis())).toString());

        if (activeDownloads.containsKey(curDownload.getUriString())) {
            return;
        }

        activeDownloads.put(curDownload.getUriString(), curDownload);
        Uri source = Uri.parse(curDownload.getUriString());
        // Uri destination = Uri.parse(this.getTemporaryFilePath());

        // attempt to attach to active download for this file (download started and we close/open the app)
        if (!attachToExistingDownload(curDownload)) {
            try {
                // make sure file does not exist, in other case DownloadManager will fail
                deleteFileIfExists(curDownload.getTempFileUri());

                DownloadManager mgr = getDownloadManager();
                DownloadManager.Request request = new DownloadManager.Request(source);
                request.setTitle(curDownload.getNotificationTitle());
                request.setVisibleInDownloadsUi(false);

                for (Map<String, String> header : curDownload.getHeaders()) {
                    request.addRequestHeader(header.get("Key"), header.get("Value"));
                }

                // Use the cookie from the webview, so the session cookie is shared
                request.addRequestHeader("Cookie", this.webView.getCookieManager().getCookie(curDownload.getUriString()));

                // hide notification. Not compatible with current android api.
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

                request.setDestinationUri(curDownload.getTempFileUri());

                curDownload.setDownloadId(mgr.enqueue(request));
            } catch (Exception ex) {
                cleanUp(curDownload, true);
                callbackContext.error(ex.getMessage());
                return;
            }
        }

        // custom logic to track file download progress
        startProgressTracking(curDownload);
    }

    private void startProgressTracking(final Download curDownload) {
        // already started
        if (curDownload.getTimerProgressUpdate() != null) {
            return;
        }
        final DownloadManager mgr = getDownloadManager();

        curDownload.setTimerProgressUpdate(new Timer());
        curDownload.getTimerProgressUpdate().schedule(new TimerTask() {
            @Override
            public void run() {
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(curDownload.getDownloadId());
                try (Cursor cursor = mgr.query(q)) {
                    if (!cursor.moveToFirst()) {
                        cleanUp(curDownload, true);
                        curDownload.reportError(ERROR_CANCELED);
                        return;
                    }

                    final int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    final int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));

                    PluginResult progressUpdate;
                    JSONObject obj;
                    switch (status) {
                        case DownloadManager.STATUS_FAILED:
                            cleanUp(curDownload, true);
                            curDownload.reportError(reason);
                            return;
                        case DownloadManager.STATUS_SUCCESSFUL:
                            handleSuccessDownload(curDownload);
                            return;
                        case DownloadManager.STATUS_RUNNING:
                            long bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            JSONObject jsonProgress = new JSONObject();
                            jsonProgress.put("bytesReceived", bytesDownloaded);
                            jsonProgress.put("totalBytesToReceive", bytesTotal);
                            obj = new JSONObject();
                            obj.put("progress", jsonProgress);
                            break;
                        case DownloadManager.STATUS_PAUSED:
                            JSONObject pauseMessage = new JSONObject();
                            pauseMessage.put("message", "Download paused with reason " + reason);
                            obj = new JSONObject();
                            obj.put("progress", pauseMessage);
                            break;
                        case DownloadManager.STATUS_PENDING:
                            JSONObject pendingMessage = new JSONObject();
                            pendingMessage.put("message", "Download pending with reason " + reason);
                            obj = new JSONObject();
                            obj.put("progress", pendingMessage);
                            break;
                        default:
                            curDownload.reportError("Unknown download state " + status);
                            return;
                    }

                    progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
                    progressUpdate.setKeepCallback(true);
                    curDownload.getCallbackContext().sendPluginResult(progressUpdate);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT);
    }

    private synchronized void cleanUp(Download curDownload, boolean shouldDeleteTargetFile) {

        if (curDownload.getTimerProgressUpdate() != null) {
            curDownload.getTimerProgressUpdate().cancel();
        }

        if (curDownload.getDownloadId() != DOWNLOAD_ID_UNDEFINED) {
            getDownloadManager().remove(curDownload.getDownloadId());
        }

        activeDownloads.remove(curDownload.getUriString());

        deleteFileIfExists(curDownload.getTempFileUri());

        if (shouldDeleteTargetFile) {
            deleteFileIfExists(curDownload.getTargetFileUri());
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    private static boolean deleteFileIfExists(Uri fileUri) {
        File targetFile = new File(fileUri.getPath());
        return targetFile.exists() && targetFile.delete();
    }

    private static String getUserFriendlyReason(int reason) {
        String failedReason = "";
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                failedReason = "ERROR_CANNOT_RESUME";
                break;
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                failedReason = "ERROR_DEVICE_NOT_FOUND";
                break;
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                failedReason = "ERROR_FILE_ALREADY_EXISTS";
                break;
            case DownloadManager.ERROR_FILE_ERROR:
                failedReason = "ERROR_FILE_ERROR";
                break;
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                failedReason = "ERROR_HTTP_DATA_ERROR";
                break;
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                failedReason = "ERROR_INSUFFICIENT_SPACE";
                break;
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                failedReason = "ERROR_TOO_MANY_REDIRECTS";
                break;
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                failedReason = "ERROR_UNHANDLED_HTTP_CODE";
                break;
            case DownloadManager.ERROR_UNKNOWN:
                failedReason = "ERROR_UNKNOWN";
                break;
            case HttpURLConnection.HTTP_BAD_REQUEST:
                failedReason = "BAD_REQUEST";
                break;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                failedReason = "UNAUTHORIZED";
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                failedReason = "FORBIDDEN";
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
                failedReason = "NOT_FOUND";
                break;
            case HttpURLConnection.HTTP_INTERNAL_ERROR:
                failedReason = "INTERNAL_SERVER_ERROR";
                break;
            case ERROR_CANCELED:
                failedReason = "CANCELED";
                break;
        }

        return failedReason;
    }

    private void stop(JSONArray args, CallbackContext callbackContext) throws JSONException {

        Download curDownload = activeDownloads.get(args.get(0).toString());
        if (curDownload == null) {
            callbackContext.error("download request not found");
            return;
        }

        curDownload.cancel();
        getDownloadManager().remove(curDownload.getDownloadId());
        callbackContext.success();
    }

    private boolean attachToExistingDownload(Download downloadItem) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_SUCCESSFUL);

        Cursor cur = getDownloadManager().query(query);

        int idxId = cur.getColumnIndex(DownloadManager.COLUMN_ID);
        int idxUri = cur.getColumnIndex(DownloadManager.COLUMN_URI);
        int idxLocalUri = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);

        final Pattern pattern = downloadItem.getUriMatcher() != null && !"".equals(downloadItem.getUriMatcher())
                ? Pattern.compile(downloadItem.getUriMatcher()) : null;

        for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
            final String existingDownloadUri = cur.getString(idxUri);
            boolean uriMatches = false;
            if (pattern != null) {
                Matcher mForExistingUri = pattern.matcher(existingDownloadUri);
                Matcher mForNewUri = pattern.matcher(downloadItem.getUriString());
                uriMatches = mForExistingUri.find() && mForNewUri.find() &&
                        mForExistingUri.group().equals(mForNewUri.group());
            }
            if (uriMatches || downloadItem.getUriString().equals(cur.getString(idxUri))) {
                downloadItem.setDownloadId(cur.getLong(idxId));
                downloadItem.setTempFileUri(cur.getString(idxLocalUri));
                break;
            }
        }
        cur.close();

        return downloadItem.getDownloadId() != DOWNLOAD_ID_UNDEFINED;
    }

    private void handleSuccessDownload(Download curDownload) {
        File sourceFile = new File(curDownload.getTempFileUri().getPath());
        File destFile = new File(curDownload.getTargetFileUri().getPath());

        boolean copyingSuccess = false;
        try {
            // try to perform rename operation first
            copyingSuccess = sourceFile.renameTo(destFile);
            if (copyingSuccess) {
                curDownload.getCallbackContext().success();
                return;
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                StorageManager storageManager =
                        cordova.getActivity().getApplicationContext().getSystemService(StorageManager.class);
                UUID appSpecificInternalDirUuid = storageManager.getUuidForPath(destFile.getParentFile());
                long availableBytes =
                        storageManager.getAllocatableBytes(appSpecificInternalDirUuid);
                if (availableBytes < sourceFile.length()) {
                    curDownload.reportError(DownloadManager.ERROR_INSUFFICIENT_SPACE);
                    return;
                }

                storageManager.allocateBytes(
                        appSpecificInternalDirUuid, sourceFile.length());
                copyFile(curDownload, sourceFile, destFile);
                copyingSuccess = true;
                return;
            }

            long usableSpace = Objects.requireNonNull(destFile.getParentFile()).getUsableSpace();
            long length = sourceFile.length();
            if (usableSpace < length) {
                curDownload.reportError(DownloadManager.ERROR_INSUFFICIENT_SPACE);
                return;
            }
            copyFile(curDownload, sourceFile, destFile);
            copyingSuccess = true;
            curDownload.getCallbackContext().success();
        } catch (InterruptedIOException e) {
            curDownload.reportError(ERROR_CANCELED);
        } catch (Exception e) {
            curDownload.reportError("Cannot copy from temporary path to actual path");
            Log.e(TAG, String.format("Error occurred while copying the file. Source: '%s'(%s), dest: '%s'", curDownload.getTempFileUri(), sourceFile.exists(), curDownload.getTargetFileUri()), e);
        } finally {
            cleanUp(curDownload, !copyingSuccess);
        }
    }

    private void copyFile(Download curDownload, File fromFile, File toFile) throws IOException {
        try (InputStream from = new FileInputStream(fromFile); OutputStream to = new FileOutputStream(toFile)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = from.read(buf)) > 0) {
                to.write(buf, 0, bytesRead);
                if (curDownload.isCanceled()) {
                    throw new InterruptedIOException("Copying terminated");
                }
            }
        }
    }

    private boolean checkPermissions(JSONArray args, CallbackContext callbackContext) {
        if (!PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            permissionRequests.put(permissionRequests.size(), new PermissionsRequest(args, callbackContext));
            PermissionHelper.requestPermission(this, permissionRequests.size() - 1, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return false;
        }

        return true;
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        final PermissionsRequest permissionsRequest = permissionRequests.get(requestCode);
        permissionRequests.remove(requestCode);
        if (permissionsRequest == null) {
            return;
        }

        if (grantResults.length < 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            permissionsRequest.callbackContext.error("PERMISSION_DENIED");
            return;
        }

        try {
            startAsync(permissionsRequest.rawArgs, permissionsRequest.callbackContext);
        } catch (JSONException ex) {
            permissionsRequest.callbackContext.error(ex.getMessage());
        }
    }
}
