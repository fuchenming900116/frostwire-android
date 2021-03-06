/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011, 2012, FrostWire(TM). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.android.gui.transfers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import android.util.Log;

import com.frostwire.android.core.Constants;
import com.frostwire.android.gui.search.SoundcloudEngineSearchResult;
import com.frostwire.android.gui.util.SystemUtils;
import com.frostwire.android.util.FileUtils;
import com.frostwire.mp3.ID3Wrapper;
import com.frostwire.mp3.ID3v1Tag;
import com.frostwire.mp3.ID3v23Tag;
import com.frostwire.mp3.Mp3File;

/**
 * @author gubatron
 * @author aldenml
 * 
 */
public class SoundcloudDownload extends TemporaryDownloadTransfer<SoundcloudEngineSearchResult> {

    private static final String TAG = "FW.SoundcloudDownload";

    private static final long MAX_ACCEPTABLE_SOUNDCLOUD_FILESIZE_FOR_COVERART_FETCH = 10485760; //10MB

    private final TransferManager manager;

    public SoundcloudDownload(TransferManager manager, SoundcloudEngineSearchResult sr) {
        this.manager = manager;
        this.sr = sr;
    }

    @Override
    public String getDisplayName() {
        return sr.getDisplayName();
    }

    @Override
    public String getStatus() {
        return delegate != null ? delegate.getStatus() : "";
    }

    @Override
    public int getProgress() {
        return delegate != null ? delegate.getProgress() : 0;
    }

    @Override
    public long getSize() {
        return delegate != null ? delegate.getSize() : 0;
    }

    @Override
    public Date getDateCreated() {
        return delegate != null ? delegate.getDateCreated() : new Date();
    }

    @Override
    public long getBytesReceived() {
        return delegate != null ? delegate.getBytesReceived() : 0;
    }

    @Override
    public long getBytesSent() {
        return delegate != null ? delegate.getBytesSent() : 0;
    }

    @Override
    public long getDownloadSpeed() {
        return delegate != null ? delegate.getDownloadSpeed() : 0;
    }

    @Override
    public long getUploadSpeed() {
        return delegate != null ? delegate.getUploadSpeed() : 0;
    }

    @Override
    public long getETA() {
        return delegate != null ? delegate.getETA() : 0;
    }

    @Override
    public boolean isComplete() {
        if (delegate != null) {
            //FIXME: we do this differently here because SoundCloud downloads may not have
            //the same number of bytes as expected at the end, or maybe we don't
            //even know exactly how many bytes to expect in the first place.
            //the fix should probably be calculating this number correctly.
            //Suggestion: maybe look at the Content-length HTTP header for a size
            //if sound cloud sends this when the download starts and update the
            //link.getSize() value with this number as it becomes known.
            return delegate.getStatusCode() == HttpDownload.STATUS_COMPLETE;
        } else {
            return false;
        }
    }

    @Override
    public List<? extends TransferItem> getItems() {
        return Collections.emptyList();
    }

    @Override
    public void cancel() {
        if (delegate != null) {
            delegate.cancel();
        }
        manager.remove(this);
    }

    @Override
    public boolean isDownloading() {
        return delegate != null ? delegate.isDownloading() : false;
    }

    @Override
    public void cancel(boolean deleteData) {
        if (delegate != null) {
            delegate.cancel(deleteData);
        }
        manager.remove(this);
    }

    public void start() {
        try {
            final HttpDownloadLink link = buildDownloadLink();
            if (link != null) {
                delegate = new HttpDownload(manager, SystemUtils.getTempDirectory(), link);
                delegate.setListener(new HttpDownloadListener() {
                    @Override
                    public void onComplete(HttpDownload download) {
                        downloadAndUpdateCoverArt(download.getSavePath());
                        scanFinalFile();
                    }
                });
                delegate.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting youtube download", e);
        }
    }


    private void downloadAndUpdateCoverArt(File tempFile) {
        //abort if file is too large.
        if (tempFile != null && tempFile.exists() && tempFile.length() <= MAX_ACCEPTABLE_SOUNDCLOUD_FILESIZE_FOR_COVERART_FETCH) {

            byte[] coverArtBytes = downloadCoverArt();
            Log.v(TAG,"cover art array length (@"+coverArtBytes.hashCode()+"): " + coverArtBytes.length);
            
            if (coverArtBytes != null && coverArtBytes.length > 0) {
                File finalFile = getFinalFile(tempFile, Constants.FILE_TYPE_AUDIO);
                if (setAlbumArt(coverArtBytes, tempFile.getAbsolutePath(), finalFile.getAbsolutePath())) {
                    tempFile.delete();
                    this.savePath = finalFile;
                } else {    
                    moveFile(tempFile,Constants.FILE_TYPE_AUDIO);
                }
            }
        } else {
            moveFile(tempFile,Constants.FILE_TYPE_AUDIO);
        }
    }
    
    private byte[] downloadCoverArt() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Log.v(TAG,"thumbnail url: " + sr.getThumbnailUrl());
            HttpDownload.simpleHTTP(sr.getThumbnailUrl(), baos, 3000);
            return baos.toByteArray();
        } catch (Throwable e) {
            Log.e(TAG,"Error downloading SoundCloud cover art.",e);
        }
        return null;
    }

    private HttpDownloadLink buildDownloadLink() throws Exception {
        HttpDownloadLink link = new HttpDownloadLink(sr.getStreamUrl());

        link.setSize(sr.getSize());
        link.setFileName(FileUtils.getValidFileName(sr.getFileName()));
        link.setDisplayName(sr.getDisplayName());
        link.setCompressed(false);

        return link;
    }
    
    private boolean setAlbumArt(byte[] imageBytes, String mp3Filename, String mp3outputFilename) {
        try {
            Mp3File mp3 = new Mp3File(mp3Filename);
            
            ID3Wrapper newId3Wrapper = new ID3Wrapper(new ID3v1Tag(), new ID3v23Tag());
            
            newId3Wrapper.setAlbum(sr.getUsername() + ": " + sr.getTitle() + " via SoundCloud.com");
            newId3Wrapper.setArtist(sr.getUsername());
            newId3Wrapper.setTitle(sr.getTitle());
            newId3Wrapper.setAlbumImage(imageBytes, "image/jpg");
            newId3Wrapper.setUrl(sr.getDetailsUrl());
            newId3Wrapper.getId3v2Tag().setPadding(true);

            mp3.setId3v1Tag(newId3Wrapper.getId3v1Tag());
            mp3.setId3v2Tag(newId3Wrapper.getId3v2Tag());
            
            mp3.save(mp3outputFilename);
            
            return true;
        } catch (Throwable e) {
            return false;
        }
    }        
}