package com.intellif.bdvdemo;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.baidu.aip.asrwakeup3.core.inputstream.InFileStream;
import com.baidu.aip.asrwakeup3.core.mini.AutoCheck;
import com.baidu.aip.asrwakeup3.core.recog.MyRecognizer;
import com.baidu.aip.asrwakeup3.core.recog.listener.IRecogListener;
import com.baidu.aip.asrwakeup3.core.recog.listener.MessageStatusRecogListener;
import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.utils.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    TextView tvText;
    ImageView ivPic;
    TextToSpeech engine;
    /**
     * 识别控制器，使用MyRecognizer控制识别的流程
     */
    protected MyRecognizer myRecognizer;

    /*
     * 本Activity中是否需要调用离线命令词功能。根据此参数，判断是否需要调用SDK的ASR_KWS_LOAD_ENGINE事件
     */
    protected boolean enableOffline = true;

    private static final String TAG = "bdvcDemoTag ";
    protected Handler handler;
    Timer timer;
    TimerTask timerTask;

    /*
     * Api的参数类，仅仅用于生成调用START的json字符串，本身与SDK的调用无关
     */
    private  CommonRecogParams apiParams = new OnlineRecogParams();

    ScheduledExecutorService executorService = Executors.newScheduledThreadPool(5);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        InFileStream.setContext(this);
        setContentView(R.layout.activity_main);
        initPermission();
        initTimer();
//        initWebSocket();
        engine = new TextToSpeech(this, this);
        engine.setOnUtteranceProgressListener(new TtsProgress());
        if( enableOffline ){
            apiParams = new OfflineRecogParams();
        }

        apiParams.initSamplePath(this);

        tvText = findViewById(R.id.et_text);
        ivPic = findViewById(R.id.iv_voice);

        ivPic.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN: {
                        //按住事件发生后执行代码的区域
                        Log.d(TAG, "iv down");
                        ivPic.setImageResource(R.drawable.ic_keyboard_voice_pressed);
                        start();
                        break;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        //移动事件发生后执行代码的区域
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        //松开事件发生后执行代码的区域
                        Log.d(TAG, "iv up");
                        ivPic.setImageResource(R.drawable.ic_keyboard_voice_nomal);
//                        stop();
                        break;
                    }

                    default:

                        break;
                }
                return true;
            }
        });
        handler = new Handler() {
            /*
             * @param msg
             */
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                Log.d(TAG, "handler msg " + msg.toString());
                if( msg.what == 6 && msg.arg1 == 6 ){
                    if( msg.obj != null && msg.obj.toString().contains("原始json：") ){
                        String split[] = msg.obj.toString().split("原始json：");
                        if( split.length > 0 ) {
                            try {
                                final JSONObject jsonObject = new JSONObject(split[1]);
                                tvText.setText(jsonObject.getString("best_result"));
                                executorService.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            String anser  = utterance(getAuth(),jsonObject.getString("best_result"));
                                            JSONObject json = new JSONObject(anser);
                                            if( json != null ){
                                                JSONObject result = json.getJSONObject("result");
                                                if(result != null){
                                                    JSONArray jsonArray = result.getJSONArray("response_list");
                                                    if(jsonArray != null && jsonArray.length() > 0){
                                                        JSONArray actionArray = jsonArray.getJSONObject(0).getJSONArray("action_list");
                                                        if( actionArray != null && actionArray.length() > 0) {
                                                            String real = actionArray.getJSONObject(0).getString("say");
                                                            Message msg = handler.obtainMessage();
                                                            msg.what = 100;
                                                            msg.obj = "\n" + real;
                                                            handler.sendMessage(msg);
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                });
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }


                if( msg.what == 100 ){
                    stop();
                    isPlaying = true;
                    tvText.append("\n" + msg.obj.toString());
                    engine.speak( msg.obj.toString(), TextToSpeech.QUEUE_FLUSH, null, "utterance");
                }

                if( msg.arg1 == 2 && msg.what == 2  ){
                    Log.i(TAG,"识别引擎结束并空闲中");
                    ivPic.setImageResource(R.drawable.ic_keyboard_voice_pressed);
                    isStart = false;
                }
            }
        };

        // 基于DEMO集成第1.1, 1.2, 1.3 步骤 初始化EventManager类并注册自定义输出事件
        // DEMO集成步骤 1.2 新建一个回调类，识别引擎会回调这个类告知重要状态和识别结果
        IRecogListener listener = new MessageStatusRecogListener(handler);
        // DEMO集成步骤 1.1 1.3 初始化：new一个IRecogListener示例 & new 一个 MyRecognizer 示例,并注册输出事件
        myRecognizer = new MyRecognizer(this, listener);
        // 基于DEMO集成1.4 加载离线资源步骤(离线时使用)。offlineParams是固定值，复制到您的代码里即可
        if (enableOffline) {
            // 基于DEMO集成1.4 加载离线资源步骤(离线时使用)。offlineParams是固定值，复制到您的代码里即可
            Map<String, Object> offlineParams = OfflineRecogParams.fetchOfflineParams();
            myRecognizer.loadOfflineEngine(offlineParams);
        }
        start();

    }

    private void initTimer() {
        timer = new Timer();
        timerTask = new TimerTask() {
            @Override
            public void run() {
                Log.i(TAG,"timer get status " + isRecording() + "  " + isPlaying());
                if(! isRecording() && !isPlaying() ){
                    Log.i(TAG," timer start record ");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            start();
                        }
                    });

                }
            }
        };
        timer.schedule(timerTask,1000,3000);
    }

    private void initWebSocket() {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)//允许失败重试
                .readTimeout(5, TimeUnit.SECONDS)//设置读取超时时间
                .writeTimeout(5, TimeUnit.SECONDS)//设置写的超时时间
                .connectTimeout(5, TimeUnit.SECONDS)//设置连接超时时间
                .build();
        String url = "ws://10.10.2.80:5570";

        Request request  = new Request.Builder().url(url).build();

        WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {//主要的几个方法(在子线程中回调,刷新UI记得使用Handler)
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                super.onOpen(webSocket, response);
                //连接成功
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                super.onMessage(webSocket, text);
                //接收服务器消息 text
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                super.onMessage(webSocket, bytes);
                //如果服务器传递的是byte类型的
                String msg = bytes.utf8();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                super.onFailure(webSocket, t, response);
                //连接失败调用 异常信息t.getMessage()
            }
        });

        client.dispatcher().executorService().shutdown();//内存不足时释放
        webSocket.send("{\n" +
                "    \"body\": {\n" +
                "        \"ctrl\": 1, \n" +
                "        \"type\": 0, \n" +
                "        \"loop\": 1,\n" +
                "        \"content\": {\n" +
                "            \"text\": \"凡凡真聪明\", \n" +
                "            \"lang\":0\n" +
                "        }\n" +
                "    }, \n" +
                "    \"cmd\": 41346, \n" +
                "    \"sn\": 27\n" +
                "}");



}

    /**
     * android 6.0 以上需要动态申请权限
     */
    private void initPermission() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                /* 下面是蓝牙用的，可以不申请
                Manifest.permission.BROADCAST_STICKY,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
                */
        };

        ArrayList<String> toApplyList = new ArrayList<>();

        for (String perm : permissions) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(this, perm)) {
                toApplyList.add(perm);
                // 进入到这里代表没有权限.

            }
        }
        String[] tmpList = new String[toApplyList.size()];
        if (!toApplyList.isEmpty()) {
            ActivityCompat.requestPermissions(this, toApplyList.toArray(tmpList), 123);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 此处为android 6.0以上动态授权的回调，用户自行实现。
    }

    protected Map<String, Object> fetchParams() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        //  上面的获取是为了生成下面的Map， 自己集成时可以忽略
        Map<String, Object> params = apiParams.fetch(sp);
        //  集成时不需要上面的代码，只需要params参数。
        return params;
    }


    boolean isStart = false;
    private boolean isRecording(){
        return isStart;
    }

    /**
     * 开始录音，点击“开始”按钮后调用。
     * 基于DEMO集成2.1, 2.2 设置识别参数并发送开始事件
     */
    protected void start() {
        // DEMO集成步骤2.1 拼接识别参数： 此处params可以打印出来，直接写到你的代码里去，最终的json一致即可。
        final Map<String, Object> params = fetchParams();
        params.put("vad.endpoint-timeout",0);
        // params 也可以根据文档此处手动修改，参数会以json的格式在界面和logcat日志中打印
        Log.i(TAG, "start record 设置的start输入参数：" + params);
        // 复制此段可以自动检测常规错误
        (new AutoCheck(getApplicationContext(), new Handler() {
            public void handleMessage(Message msg) {
                if (msg.what == 100) {
                    AutoCheck autoCheck = (AutoCheck) msg.obj;
                    synchronized (autoCheck) {
                        String message = autoCheck.obtainErrorMessage(); // autoCheck.obtainAllMessage();

                        ; // 可以用下面一行替代，在logcat中查看代码
                        Log.w("AutoCheckMessage", message);
                    }
                }
            }
        }, enableOffline)).checkAsr(params);

        // 这里打印出params， 填写至您自己的app中，直接调用下面这行代码即可。
        // DEMO集成步骤2.2 开始识别
        myRecognizer.start(params);
        ivPic.setImageResource(R.drawable.ic_keyboard_voice_nomal);
        isStart = true;
    }

    /**
     * 开始录音后，手动点击“停止”按钮。
     * SDK会识别不会再识别停止后的录音。
     * 基于DEMO集成4.1 发送停止事件 停止录音
     */
    protected void stop() {
        Log.i(TAG, "end record " );
        myRecognizer.stop();
        ivPic.setImageResource(R.drawable.ic_keyboard_voice_pressed);
        isStart = false;
    }

    /**
     * 开始录音后，手动点击“取消”按钮。
     * SDK会取消本次识别，回到原始状态。
     * 基于DEMO集成4.2 发送取消事件 取消本次识别
     */
    protected void cancel() {

        myRecognizer.cancel();
    }

    /**
     * 销毁时需要释放识别资源。
     */
    @Override
    protected void onDestroy() {

        // 如果之前调用过myRecognizer.loadOfflineEngine()， release()里会自动调用释放离线资源
        // 基于DEMO5.1 卸载离线资源(离线时使用) release()方法中封装了卸载离线资源的过程
        // 基于DEMO的5.2 退出事件管理器
        myRecognizer.release();

        Log.i(TAG, "onDestory");

        // BluetoothUtil.destory(this); // 蓝牙关闭

        super.onDestroy();
    }



    private  String utterance(String token, String question) {
        // 请求URL
        String talkUrl = "https://aip.baidubce.com/rpc/2.0/unit/service/chat";
        try {
            // 请求参数
            String params = "{\"log_id\":\"UNITTEST_10000\",\"version\":\"2.0\",\"service_id\":\"S41730\",\"session_id\":\"\",\"request\":{\"query\":\"" + question +"\",\"user_id\":\"88888\"},\"dialog_state\":{\"contexts\":{\"SYS_REMEMBERED_SKILLS\":[\"1057\"]}}}";
            String accessToken = token;
            String result = HttpUtil.post(talkUrl, accessToken, "application/json", params);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    public static String getAuth() {
        // 官网获取的 API Key 更新为你注册的
        String clientId = "OO3Hud0q00uxgyUGM00YPcgC";
        // 官网获取的 Secret Key 更新为你注册的
        String clientSecret = "Bzlrvc7VGm1Iv8r5CAjT5BHHFk3OfT3D";
        return getAuth(clientId, clientSecret);
    }

    /**
     * 获取API访问token
     * 该token有一定的有效期，需要自行管理，当失效时需重新获取.
     * @param ak - 百度云官网获取的 API Key
     * @param sk - 百度云官网获取的 Securet Key
     * @return assess_token 示例：
     * "24.460da4889caad24cccdb1fea17221975.2592000.1491995545.282335-1234567"
     */
    public static String getAuth(String ak, String sk) {
        // 获取token地址
        String authHost = "https://aip.baidubce.com/oauth/2.0/token?";
        String getAccessTokenUrl = authHost
                // 1. grant_type为固定参数
                + "grant_type=client_credentials"
                // 2. 官网获取的 API Key
                + "&client_id=" + ak
                // 3. 官网获取的 Secret Key
                + "&client_secret=" + sk;
        try {
            URL realUrl = new URL(getAccessTokenUrl);
            // 打开和URL之间的连接
            HttpURLConnection connection = (HttpURLConnection) realUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            // 获取所有响应头字段
            Map<String, List<String>> map = connection.getHeaderFields();
            // 遍历所有的响应头字段
            for (String key : map.keySet()) {
                System.err.println(key + "--->" + map.get(key));
            }
            // 定义 BufferedReader输入流来读取URL的响应
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String result = "";
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
            /**
             * 返回结果示例
             */
            System.err.println("result:" + result);
            JSONObject jsonObject = new JSONObject(result);
            String access_token = jsonObject.getString("access_token");
            return access_token;

        } catch (Exception e) {
            System.err.printf("获取token失败！");
            e.printStackTrace(System.err);
        }
        return null;
    }


    @Override
    public void onInit(int i) {
        if (i == TextToSpeech.SUCCESS) {
            engine.setLanguage(Locale.CHINA);

        }
    }

    boolean isPlaying = false;
    private boolean isPlaying(){
        return isPlaying;
    }

    private class TtsProgress extends UtteranceProgressListener {

        @Override
        public void onStart(String s) {
            Log.e(TAG, "======onStart: 开始" );
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //在主界面操作
                }
            });
            isPlaying = true;
        }

        @Override
        public void onDone(String s) {
            Log.e(TAG, "======onDone: 结束" );
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //在主界面操作
                }
            });
            isPlaying = false;
        }

        @Override
        public void onError(String s) {
            Log.e(TAG, "======onError: 错误" );
        }
    }

}

/**
 02-26 14:43:35.551 4502-4681/com.intellif.bdvdemo E/bdvcDemoTag: ======onDone: 结束
 02-26 14:43:35.551 4502-4502/com.intellif.bdvdemo I/bdvcDemoTag: start record 设置的start输入参数：{decoder=2, vad.endpoint-timeout=0}
 02-26 14:43:35.581 4502-4502/com.intellif.bdvdemo D/bdvcDemoTag: handler msg { when=0 what=3 arg1=3 obj=[wp.ready]引擎就绪，可以开始说话。  ;time=1614321815593
 target=com.intellif.bdvdemo.MainActivity$2 }
 02-26 14:43:37.031 4502-4502/com.intellif.bdvdemo D/bdvcDemoTag: handler msg { when=0 what=4 arg1=4 obj=[asr.begin]检测到用户说话  ;time=1614321817039
 target=com.intellif.bdvdemo.MainActivity$2 }
 02-26 14:43:38.721 4502-4502/com.intellif.bdvdemo D/bdvcDemoTag: handler msg { when=0 what=6 arg1=6 obj=[asr.partial]【asr.finish事件】识别错误, 错误码：10 ,10012 ; Offline engine recognize fail[KWS] failed to recognition.
 target=com.intellif.bdvdemo.MainActivity$2 }
 02-26 14:43:38.721 4502-4502/com.intellif.bdvdemo D/bdvcDemoTag: handler msg { when=0 what=6 arg1=6 arg2=1 obj=【asr.finish事件】识别错误, 错误码：10 ,10012 ; Offline engine recognize fail[KWS] failed to recognition.
 target=com.intellif.bdvdemo.MainActivity$2 }
 02-26 14:43:38.731 4502-4502/com.intellif.bdvdemo D/bdvcDemoTag: handler msg { when=-1ms what=2 arg1=2 obj=[asr.exit]识别引擎结束并空闲中  ;time=1614321818736
 target=com.intellif.bdvdemo.MainActivity$2 }

 **/