package com.autoupdateapk;

import android.app.DownloadManager;
import android.net.Uri;

/**
 * Created by Collapick-Trainee on 21/09/2017.
 */

public interface UpdateManagerListener {

    /*
        Called when the download has been started.
     */
    void updateManagerDownloadStarted();

    /*
        Called every time when an error has been reached.
        INPUT:
        message is a String with little more details.
     */
    void updateManagerError(String message);

    // Called when download was cancelled
    void updateManagerCancelled();

    /*
     Starts an intent (in this case installation) for the downloaded APK file.
     Check's for API level to support both API 23=> and API 24=<.

     INPUT:
     Uri from DownloadManager's COLUMN_LOCAL_URI,
     MimeType from DownloadManager's COLUMN_MEDIA_TYPE
    */
    void updateManagerFinished(Uri uri, String mimeType);
}
