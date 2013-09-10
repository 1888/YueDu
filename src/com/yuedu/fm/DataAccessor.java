package com.yuedu.fm;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by dong on 13-9-9.
 */
public enum  DataAccessor {
    SINGLE_INSTANCE;

    private List<TuneInfo> mDataList;
    private int mPlayingTuneIndex;
    private AsyncHttpClient mHTTPClient;

    public static final String DATA_ACCESSOR_DOWNLOAD_COMPLETE_ACTION = "download_complete";
    public static final String DATA_ACCESSOR_DOWNLOAD_FAILED_ACTION = "download_failed";

    public List<TuneInfo> getDataList() {
        return mDataList;
    }

    public TuneInfo getPlayingTune() {
        return mDataList.get(mPlayingTuneIndex);
    }

    public int getPlayingTuneIndex() {
        return mPlayingTuneIndex;
    }

    public synchronized void playTuneAtIndex(int index) {
        if (index >= 0 && index < mDataList.size()) {
            mPlayingTuneIndex = index;
        }else {
            throw new IndexOutOfBoundsException("index "+index +" is out of data list bounds!");
        }
    }

    public synchronized void playNextTune() {
        mPlayingTuneIndex += 1;
    }

    private AsyncHttpClient getClient() {
        if (mHTTPClient == null) {
            mHTTPClient = new AsyncHttpClient();
        }
        return mHTTPClient;
    }

    private synchronized void setDataList(JSONArray jsonArray,Context context) {
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length() ; i++) {
                try {
                    JSONObject tuneJSON = jsonArray.getJSONObject(i);
                    if (tuneJSON != null) {
                        mDataList.add(new TuneInfo(tuneJSON));
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(DATA_ACCESSOR_DOWNLOAD_COMPLETE_ACTION));
        }else {
            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(DATA_ACCESSOR_DOWNLOAD_FAILED_ACTION));
        }
    }

    public void downloadData(final Context context) {
        RequestParams param = new RequestParams("data", "playlist");
        getClient().get("http://yuedu.fm/", param, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(JSONObject jsonObject) {
                super.onSuccess(jsonObject);
                JSONArray al = jsonObject.optJSONArray("list");
                setDataList(al,context);
            }

            @Override
            public void onFailure(Throwable throwable, JSONObject jsonObject) {
                super.onFailure(throwable, jsonObject);
                LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(DATA_ACCESSOR_DOWNLOAD_FAILED_ACTION));
            }
        });
    }

    private DataAccessor() {
        mDataList = new ArrayList<TuneInfo>(200);
    }

}