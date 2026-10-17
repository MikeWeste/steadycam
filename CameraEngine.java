package com.example.steadycam;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.*;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** All camera/recorder state is confined to one HandlerThread. Media I/O has a separate queue. */
final class CameraEngine {
    enum State { CLOSED, OPENING, CONFIGURING, READY, PHOTO, PREPARING, RECORDING, PAUSED, STOPPING }
    interface Listener {
        void onInfo(Info info);
        void onState(State state);
        void onMessage(String message);
        void onFrame(double fps, String stabilization);
        void onSaved(Uri uri, boolean video, String summary);
        void onFocus(boolean success, boolean locked);
    }
    static final class Mode {
        final Size size, preview;
        final int fps;
        final Range<Integer> range;
        Mode(Size s, Size p, int f, Range<Integer> r) { size=s; preview=p; fps=f; range=r; }
        String label() { return resolution()+" · "+(range.getLower()<fps?"до ":"")+fps+" к/с"; }
        String resolution() { return size.getHeight()==2160?"4K":size.getHeight()==1080?"FHD":"HD"; }
        String key() { return size.getWidth()+"x"+size.getHeight()+"@"+fps; }
    }
    static final class Info {
        final List<Mode> modes;
        final Mode mode;
        final String cameraId, cameraName, stabilization, mime;
        final List<String> ids, names;
        final boolean front, flash, eis, ois, audio, video, aeLock, photoMax;
        final float minZoom, maxZoom, zoom, evStep;
        final int evMin, evMax, ev, sensor, previewWidth, previewHeight, flashMode;
        Info(CameraEngine e) {
            modes=new ArrayList<>(e.modes); mode=e.selected; cameraId=e.id; cameraName=e.cameraName(e.id);
            ids=new ArrayList<>(e.ids); names=new ArrayList<>(); for(String s:ids) names.add(e.cameraName(s));
            front=e.front; flash=e.flashAvailable; eis=e.eisAvailable; ois=e.oisAvailable;
            audio=e.audio; video=e.video; aeLock=e.locked; photoMax=e.highPhoto; minZoom=e.minZoom; maxZoom=e.maxZoom; zoom=e.zoom;
            evMin=e.evMin; evMax=e.evMax; ev=e.ev; evStep=e.evStep; sensor=e.sensor; mime=e.mime;
            stabilization=e.stabilization; flashMode=e.flashMode;
            Size p=e.previewSize; previewWidth=p==null?1280:p.getWidth(); previewHeight=p==null?720:p.getHeight();
        }
    }
    private final Context context;
    private final CameraManager manager;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("SteadyCam-camera");
    private final Handler h;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final List<String> ids=new ArrayList<>();
    private final List<Mode> modes=new ArrayList<>();
    private final Map<String,String> rejected=new LinkedHashMap<>();
    private final Map<String,MediaCodecInfo.VideoCapabilities> codecCache=new HashMap<>();
    private final StringBuilder eventLog=new StringBuilder();
    private volatile State state=State.CLOSED;
    private int surfaceWidth=0,surfaceHeight=0;
    private boolean pendingRecording=false;
    private volatile boolean disposed=false;
    private boolean active=false, opening=false, front=false, video=true, audio=true;
    private boolean flashAvailable, eisAvailable, oisAvailable, aeLockAvailable, locked, focusActive;
    private boolean highPhoto=false, stabilizationRetry=false, sessionStabilizationOff=false;
    private String id, mime="video/avc", stabilization="AUTO";
    private CameraCharacteristics ch;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface, recordSurface;
    private ImageReader reader;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFd;
    private Uri videoUri;
    private Mode selected;
    private Size previewSize;
    private int generation=0, sessionNumber=0, displayDegrees=0, sensor=90, flashMode=0;
    private int evMin, evMax, ev=0, photoWaiting=0;
    private float evStep=0, minZoom=1, maxZoom=1, zoom=1;
    private Rect focusRegion;
    private long firstFrame=0, frameCount=0, lastFrame=0;
    private long startMs=0, pauseStart=0, pausedMs=0;
    private String actualStabilization="—";
    private int fallbackAfMode=CaptureRequest.CONTROL_AF_MODE_OFF;
    private volatile long publishedElapsed=0;
    private long captureStarted=0;
    private int photoRotation=0;
    private int notifiedAf=-1;
    private boolean notifiedFocusLock=false,photoSaving=false;

    CameraEngine(Context c, Listener l) {
        context=c.getApplicationContext(); listener=l; manager=(CameraManager)c.getSystemService(Context.CAMERA_SERVICE);
        prefs=c.getSharedPreferences("camera_v2",Context.MODE_PRIVATE);
        video=prefs.getBoolean("video",false); stabilization=prefs.getString("stabilization","AUTO");
        mime=prefs.getString("mime","video/avc"); flashMode=prefs.getInt("flashMode",0);
        highPhoto=prefs.getBoolean("highPhoto",false);
        thread.start(); h=new Handler(thread.getLooper());
    }
    private void ui(Runnable r) { main.post(()->{if(!disposed) r.run();}); }
    private void message(String m) { log(m); ui(()->listener.onMessage(m)); }
    private void log(String s) { if(eventLog.length()>12000) eventLog.delete(0,6000); eventLog.append(SystemClock.elapsedRealtime()).append(' ').append(s).append('\n'); }
    private void state(State s) { state=s; ui(()->listener.onState(s)); }
    private void publish() { Info i=new Info(this); ui(()->listener.onInfo(i)); }
    boolean isBusyRecording() { return state==State.PREPARING || state==State.RECORDING || state==State.PAUSED || state==State.STOPPING; }
    private boolean configurable() { return !isBusyRecording() && state!=State.PHOTO; }
    void attach(Surface surface,int width,int height) { h.post(()->{boolean changed=surface!=previewSurface||width!=surfaceWidth||height!=surfaceHeight;previewSurface=surface;surfaceWidth=width;surfaceHeight=height;if(active){if(device==null)open();else if(changed&&(state==State.CONFIGURING||state==State.PREPARING))configure(pendingRecording);}}); }
    void detach() { h.post(()->{if(isBusyRecording()) stopRecording(false); close(); previewSurface=null;}); }
    void resume() { h.post(()->{active=true; open();}); }
    void suspend() { h.post(()->{active=false; if(isBusyRecording()) stopRecording(false); close();}); }
    void destroy() { h.post(()->{active=false; if(isBusyRecording()) stopRecording(false); close(); disposed=true; io.shutdown(); thread.quitSafely();}); }
    void orientation(int degrees) { h.post(()->{displayDegrees=degrees;}); }
    void setAudio(boolean value) { h.post(()->{if(configurable()) {audio=value;publish();}}); }
    void mode(boolean value) { h.post(()->{if(!configurable()||video==value)return;video=value;locked=false;focusActive=false;focusRegion=null; prefs.edit().putBoolean("video",value).apply(); configure(false);publish();}); }
    void selectMode(String key) { h.post(()->{if(!configurable())return;for(Mode m:modes)if(m.key().equals(key)){selected=m;prefs.edit().putString("mode_"+id,key).apply();locked=false;configure(false);publish();break;}}); }
    void flip() { h.post(()->{if(!configurable())return;String next=null;for(String candidate:ids)try{if((manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT)!=front){next=candidate;break;}}catch(Exception ignored){}if(next==null){message("Другая камера не предоставлена Android");return;}close();id=next;open();}); }
    void camera(String value) { h.post(()->{if(!configurable()||!ids.contains(value))return;close();id=value;open();}); }
    void zoom(float value) { h.post(()->{if(state==State.PREPARING||state==State.STOPPING||state==State.PHOTO)return;zoom=CameraPolicy.clamp(value,minZoom,maxZoom);focusRegion=null;refresh();}); }
    void exposure(int value) { h.post(()->{ev=Math.max(evMin,Math.min(evMax,value));if(locked){locked=false;publish();}refresh();}); }
    void stabilization(String value) { h.post(()->{if(!configurable())return;stabilization=value;sessionStabilizationOff=false;stabilizationRetry=false;prefs.edit().putString("stabilization",value).apply();configure(false);publish();}); }
    void codec(String value) { h.post(()->{if(!configurable())return;mime=value;discover();prefs.edit().putString("mime",value).apply();configure(false);publish();}); }
    void force60(boolean value) { h.post(()->{if(!configurable())return;prefs.edit().putBoolean("force60",value).apply();discover();configure(false);publish();message(value?"Эксперимент: 60 к/с запрашивается принудительно. Проверь счётчик и итог MP4":"Принудительный 60 к/с выключен");}); }
    void highPhoto(boolean value) { h.post(()->{if(!configurable())return;highPhoto=value;prefs.edit().putBoolean("highPhoto",value).apply();if(!video)configure(false);publish();}); }
    boolean highPhoto() { return highPhoto; }
    void flash(int value) { h.post(()->{if(!configurable()||!flashAvailable)return;flashMode=value;prefs.edit().putInt("flashMode",value).apply();refresh();publish();}); }
    void unlock() { h.post(()->{locked=false;focusActive=false;focusRegion=null;cancelAf();refresh();publish();message("Автофокус и автоэкспозиция");}); }
    private void open() {
        if(!active||previewSurface==null||!previewSurface.isValid()||device!=null||opening)return;
        if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try {
            ids.clear();ids.addAll(Arrays.asList(manager.getCameraIdList()));
            if(id==null||!ids.contains(id)) {
                for(String candidate:ids)if(Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING))){id=candidate;break;}
                if(id==null&&!ids.isEmpty())id=ids.get(0);
            }
            if(id==null){message("Android не предоставил камеры");state(State.CLOSED);return;}
            ch=manager.getCameraCharacteristics(id);
            front=Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(ch.get(CameraCharacteristics.LENS_FACING));
            sensor=get(ch.get(CameraCharacteristics.SENSOR_ORIENTATION),90);
            discover();state(State.OPENING);opening=true;
            int epoch=++generation;
            manager.openCamera(id,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){if(epoch!=generation||!active){c.close();return;}opening=false;device=c;configure(false);}
                public void onDisconnected(CameraDevice c){c.close();if(device==c){device=null;if(isBusyRecording())stopRecording(true);close();message("Камера отключена. Открой приложение снова.");}}
                public void onError(CameraDevice c,int error){c.close();if(epoch!=generation)return;opening=false;device=null;if(isBusyRecording())stopRecording(true);close();message("Камера недоступна ("+error+"). Закрой другие приложения камеры.");}
            },h);
        } catch(Exception e){opening=false;state(State.CLOSED);message("Открытие камеры: "+e.getMessage());}
    }
    private static int get(Integer value,int fallback){return value==null?fallback:value;}
    private static boolean contains(int[] values,int target){if(values!=null)for(int v:values)if(v==target)return true;return false;}
    private String cameraName(String cameraId) {
        if(cameraId==null)return "Камера";
        try {
            CameraCharacteristics c=manager.getCameraCharacteristics(cameraId);
            boolean f=Integer.valueOf(CameraCharacteristics.LENS_FACING_FRONT).equals(c.get(CameraCharacteristics.LENS_FACING));
            float[] fl=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF physical=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            String focal=fl!=null&&fl.length>0&&physical!=null?String.format(Locale.US," · %.0f мм",fl[0]*36/physical.getWidth()):"";
            return (f?"Фронтальная":"Основная")+focal+" ("+cameraId+")";
        }catch(Exception e){return "Камера "+cameraId;}
    }
    private void discover() {
        if(ch==null)return;
        String wanted=prefs.getString("mode_"+id,"1920x1080@30");
        if(selected!=null&&id.equals(prefs.getString("lastCamera","")))wanted=selected.key();
        prefs.edit().putString("lastCamera",id).apply();
        modes.clear();rejected.clear();selected=null;codecCache.clear();
        flashAvailable=Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        eisAvailable=contains(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        oisAvailable=contains(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        aeLockAvailable=Boolean.TRUE.equals(ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        int[] af=ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        fallbackAfMode=contains(af,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:contains(af,CaptureRequest.CONTROL_AF_MODE_AUTO)?CaptureRequest.CONTROL_AF_MODE_AUTO:CaptureRequest.CONTROL_AF_MODE_OFF;
        minZoom=1;Float z=ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);maxZoom=z==null?1:Math.min(z,20);
        if(Build.VERSION.SDK_INT>=30){Range<Float> zr=ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);if(zr!=null){minZoom=Math.max(.1f,zr.getLower());maxZoom=Math.min(zr.getUpper(),20);}}
        zoom=CameraPolicy.clamp(1,minZoom,maxZoom);sessionStabilizationOff=false;stabilizationRetry=false;locked=false;focusRegion=null;focusActive=false;
        Range<Integer> er=ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        evMin=er==null?0:er.getLower();evMax=er==null?0:er.getUpper();ev=0;
        Rational step=ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);evStep=step==null?0:step.floatValue();
        Range<Integer>[] ranges=ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int[][] raw=new int[ranges==null?0:ranges.length][2];for(int i=0;i<raw.length;i++){raw[i][0]=ranges[i].getLower();raw[i][1]=ranges[i].getUpper();}
        StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes=map==null?null:map.getOutputSizes(MediaRecorder.class);
        for(int[] wh:new int[][]{{1280,720},{1920,1080},{3840,2160}})for(int fps:new int[]{30,60}) {
            Size s=new Size(wh[0],wh[1]);String key=s+"@"+fps;String reason=null;int index=CameraPolicy.bestRange(raw,fps);
            if(sizes==null||!Arrays.asList(sizes).contains(s))reason="Камера не отдаёт это разрешение в Camera2";
            else if(index<0&&!(fps==60&&prefs.getBoolean("force60",false)))reason="Прошивка не объявила диапазон до "+fps+" к/с в обычной Camera2-сессии";
            else if(!CameraPolicy.supportsRate(duration(map,MediaRecorder.class,s),fps))reason="Поток записи ограничен временем кадра: "+duration(map,MediaRecorder.class,s)+" нс";
            else if(!encoderSupports(s,fps))reason="Нет доступного "+(mime.equals("video/avc")?"H.264":"HEVC")+"-кодировщика для этого режима";
            Size p=reason==null?choosePreview(map,s,fps):null;
            if(reason==null&&p==null)reason="Нет совместимого потока предпросмотра для "+fps+" к/с";
            if(reason!=null)rejected.put(key,reason);else modes.add(new Mode(s,p,fps,index>=0?ranges[index]:Range.create(60,60)));
        }
        for(Mode m:modes)if(m.key().equals(wanted))selected=m;
        if(selected==null)for(Mode m:modes)if(m.key().equals("1920x1080@30"))selected=m;
        if(selected==null&&!modes.isEmpty())selected=modes.get(0);
        publish();
    }
    private long duration(StreamConfigurationMap map,Class<?> klass,Size s){try{return map.getOutputMinFrameDuration(klass,s);}catch(Exception e){return 0;}}
    private Size choosePreview(StreamConfigurationMap map,Size target,int fps) {
        Size[] all=map.getOutputSizes(SurfaceHolder.class);if(all==null)return null;
        Size best=null;long score=Long.MAX_VALUE;
        for(Size s:all){
            if(s.getWidth()>1920||s.getHeight()>1080)continue;
            if(Math.abs((double)s.getWidth()/s.getHeight()-(double)target.getWidth()/target.getHeight())>.035)continue;
            if(!CameraPolicy.supportsRate(duration(map,SurfaceHolder.class,s),fps))continue;
            long area=(long)s.getWidth()*s.getHeight(), desired=Math.min((long)target.getWidth()*target.getHeight(),1920L*1080);
            long cost=Math.abs(area-desired);if(cost<score){best=s;score=cost;}
        }
        return best;
    }
    private boolean encoderSupports(Size size,int fps) {
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(!info.isEncoder())continue;
            try{MediaCodecInfo.VideoCapabilities cap=info.getCapabilitiesForType(mime).getVideoCapabilities();
                if(cap!=null&&cap.areSizeAndRateSupported(size.getWidth(),size.getHeight(),fps)){codecCache.put(size+"@"+fps,cap);return true;}
            }catch(Exception ignored){}
        }
        return false;
    }
    private Size photoSize(){
        StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] all=map==null?null:map.getOutputSizes(ImageFormat.JPEG);if(all==null||all.length==0)return new Size(1920,1080);
        List<Size> list=new ArrayList<>(Arrays.asList(all));list.sort((a,b)->Long.compare((long)b.getWidth()*b.getHeight(),(long)a.getWidth()*a.getHeight()));
        if(highPhoto)return list.get(0);
        for(Size s:list)if((long)s.getWidth()*s.getHeight()<=13_000_000L)return s;
        return list.get(list.size()-1);
    }
    private void configure(boolean recording) {
        if(device==null||!active||previewSurface==null||!previewSurface.isValid())return;
        final int token=++sessionNumber,epoch=generation;
        closeSession();
        if(video&&selected==null){state(State.READY);publish();message("Нет совместимых видеорежимов. Открой диагностику или режим Фото.");return;}
        try {
            StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize=video?selected.preview:choosePreview(map,photoSize(),30);
            if(previewSize==null){Size[] p=map.getOutputSizes(SurfaceHolder.class);if(p==null||p.length==0)throw new IOException("Нет предпросмотра");previewSize=Arrays.stream(p).min(Comparator.comparingLong(z->(long)z.getWidth()*z.getHeight())).orElse(p[0]);}
            pendingRecording=recording;
            if(surfaceWidth!=previewSize.getWidth()||surfaceHeight!=previewSize.getHeight()){
                state(recording?State.PREPARING:State.CONFIGURING);publish();h.postDelayed(()->{if(token==sessionNumber&&(state==State.CONFIGURING||state==State.PREPARING))sessionFailure(recording,"Размер предпросмотра не подтверждён Android");},8000);return;
            }
            ArrayList<OutputConfiguration> outputs=new ArrayList<>();outputs.add(new OutputConfiguration(previewSurface));
            if(recording){recordSurface=recorder.getSurface();outputs.add(new OutputConfiguration(recordSurface));}
            else if(!video){Size jpeg=photoSize();reader=ImageReader.newInstance(jpeg.getWidth(),jpeg.getHeight(),ImageFormat.JPEG,2);reader.setOnImageAvailableListener(this::savePhoto,h);outputs.add(new OutputConfiguration(reader.getSurface()));}
            publish();
            state(recording?State.PREPARING:State.CONFIGURING);
            SessionConfiguration config=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,r->h.post(r),new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){
                    if(token!=sessionNumber||epoch!=generation||!active||device==null){s.close();return;}session=s;
                    try{firstFrame=0;frameCount=0;lastFrame=0;s.setRepeatingRequest(request(recording).build(),captureCallback,h);
                        if(recording){recorder.start();startMs=SystemClock.elapsedRealtime();pausedMs=0;pauseStart=0;state(State.RECORDING);message("Запись · "+selected.label());}
                        else {state(State.READY);message(locked?"AF/AE LOCK":"Камера готова");}
                    }catch(Exception e){sessionFailure(recording,"Запуск: "+e.getMessage());}
                }
                public void onConfigureFailed(CameraCaptureSession s){s.close();if(token==sessionNumber)sessionFailure(recording,"Прошивка отклонила сочетание потоков");}
            });
            CaptureRequest.Builder params=device.createCaptureRequest(video?CameraDevice.TEMPLATE_RECORD:CameraDevice.TEMPLATE_PREVIEW);
            if(video&&selected!=null)params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,selected.range);
            applyStabilization(params);
            config.setSessionParameters(params.build());
            device.createCaptureSession(config);
            h.postDelayed(()->{if(token==sessionNumber&&(state==State.PREPARING||state==State.CONFIGURING))sessionFailure(recording,"Камера не ответила за 8 секунд");},8000);
        }catch(Exception e){sessionFailure(recording,"Сессия: "+e.getMessage());}
    }
    private void sessionFailure(boolean recording,String reason){
        log(reason);
        if(recording){cleanupRecorder(false);}
        if(!sessionStabilizationOff&&!stabilizationRetry){sessionStabilizationOff=true;stabilizationRetry=true;message("Пробую этот режим без стабилизации");
            if(recording){state(State.READY);startRecordingInternal();}else configure(false);return;}
        stabilizationRetry=false;state(State.READY);message(reason+". Выбери другой режим; частота не подменена на 30.");
        if(recording)configure(false);
    }
    private CaptureRequest.Builder request(boolean recording)throws CameraAccessException {
        CaptureRequest.Builder b=device.createCaptureRequest(video?CameraDevice.TEMPLATE_RECORD:CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);if(recording&&recordSurface!=null)b.addTarget(recordSurface);
        applyControls(b,false);return b;
    }
    private void applyControls(CaptureRequest.Builder b,boolean still) {
        b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        int[] af=ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int wanted=focusActive?CaptureRequest.CONTROL_AF_MODE_AUTO:video?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        b.set(CaptureRequest.CONTROL_AF_MODE,contains(af,wanted)?wanted:fallbackAfMode);
        b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        int ae=CaptureRequest.CONTROL_AE_MODE_ON;
        if(!video&&flashAvailable){if(flashMode==1)ae=CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;else if(flashMode==2)ae=CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;}
        int[] aeModes=ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        b.set(CaptureRequest.CONTROL_AE_MODE,contains(aeModes,ae)?ae:CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ev);
        if(aeLockAvailable)b.set(CaptureRequest.CONTROL_AE_LOCK,locked);
        if(video&&selected!=null)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,selected.range);
        if(flashAvailable)b.set(CaptureRequest.FLASH_MODE,video&&flashMode==2?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
        Rect crop=crop();
        if(Build.VERSION.SDK_INT>=30&&ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!=null)b.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom);
        else if(crop!=null)b.set(CaptureRequest.SCALER_CROP_REGION,crop);
        if(focusRegion!=null){MeteringRectangle[] regions={new MeteringRectangle(focusRegion,MeteringRectangle.METERING_WEIGHT_MAX-1)};
            if(get(ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF),0)>0)b.set(CaptureRequest.CONTROL_AF_REGIONS,regions);
            if(get(ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE),0)>0)b.set(CaptureRequest.CONTROL_AE_REGIONS,regions);}
        applyStabilization(b);
        if(still){b.set(CaptureRequest.JPEG_ORIENTATION,photoRotation);b.set(CaptureRequest.JPEG_QUALITY,(byte)97);
            // Still frames are not real-time: ask the ISP for its best denoise/sharpen path when the HAL offers it.
            if(contains(ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))b.set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            if(contains(ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),CaptureRequest.EDGE_MODE_HIGH_QUALITY))b.set(CaptureRequest.EDGE_MODE,CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            if(contains(ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY))b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            if(contains(ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY))b.set(CaptureRequest.DISTORTION_CORRECTION_MODE,CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);}
    }
    private void applyStabilization(CaptureRequest.Builder b){
        boolean off=sessionStabilizationOff||"OFF".equals(stabilization)||!video;
        boolean useEis=!off&&eisAvailable&&("EIS".equals(stabilization)||("AUTO".equals(stabilization)&&selected!=null&&selected.fps<=30));
        boolean useOis=!off&&!useEis&&oisAvailable&&(!"EIS".equals(stabilization));
        if(eisAvailable)b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,useEis?CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON:CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if(oisAvailable)b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,useOis?CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON:CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        // In photo mode optical stabilization is useful without electronic video cropping.
        if(!video&&oisAvailable)b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }
    private Rect crop(){Rect a=ch==null?null:ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);if(a==null)return null;float z=Math.max(1,zoom);int w=(int)(a.width()/z),hh=(int)(a.height()/z);return new Rect(a.centerX()-w/2,a.centerY()-hh/2,a.centerX()+w/2,a.centerY()+hh/2);}
    private void refresh(){if(session==null||device==null||state==State.PREPARING||state==State.STOPPING||state==State.PHOTO)return;try{session.setRepeatingRequest(request(state==State.RECORDING||state==State.PAUSED).build(),captureCallback,h);}catch(Exception e){message("Настройка не применена: "+e.getMessage());}}
    void focus(float x,float y,boolean lock){h.post(()->{
        if(device==null||session==null||state==State.PHOTO||state==State.PREPARING||state==State.STOPPING)return;
        try{Rect area=ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);if(area==null)return;
            // With CONTROL_ZOOM_RATIO, metering coordinates are in the zoomed field-of-view coordinate space.
            Rect bounds=Build.VERSION.SDK_INT>=30&&ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!=null?new Rect(area):crop();
            double[] p=CameraPolicy.sensorPoint(x,y,CameraPolicy.previewRotation(sensor,displayDegrees,front),front);
            // The stream may crop the sensor to 16:9; map through that center crop first.
            double aspect=(double)previewSize.getWidth()/previewSize.getHeight();
            if((double)bounds.width()/bounds.height()>aspect){int w=(int)(bounds.height()*aspect);bounds=new Rect(bounds.centerX()-w/2,bounds.top,bounds.centerX()+w/2,bounds.bottom);}
            else {int hh=(int)(bounds.width()/aspect);bounds=new Rect(bounds.left,bounds.centerY()-hh/2,bounds.right,bounds.centerY()+hh/2);}
            int w=Math.max(2,bounds.width()/9),hh=Math.max(2,bounds.height()/9);
            int left=(int)CameraPolicy.clamp((float)(bounds.left+p[0]*bounds.width()-w/2),bounds.left,bounds.right-w);
            int top=(int)CameraPolicy.clamp((float)(bounds.top+p[1]*bounds.height()-hh/2),bounds.top,bounds.bottom-hh);
            focusRegion=new Rect(left,top,left+w,top+hh);notifiedAf=-1;locked=lock;focusActive=contains(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_AUTO);
            CaptureRequest.Builder b=request(state==State.RECORDING||state==State.PAUSED);if(focusActive){b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);session.capture(b.build(),captureCallback,h);}refresh();publish();
            if(lock)message(aeLockAvailable?"AF/AE LOCK · касание снимает фиксацию":"Фокус зафиксирован · AE lock не поддерживается");
            if(!lock){int token=sessionNumber;h.postDelayed(()->{if(token==sessionNumber&&!locked){focusActive=false;cancelAf();refresh();}},4000);}
        }catch(Exception e){message("Фокус: "+e.getMessage());}
    });}
    private void cancelAf(){if(session==null||device==null)return;try{CaptureRequest.Builder b=request(state==State.RECORDING||state==State.PAUSED);b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);session.capture(b.build(),null,h);}catch(Exception ignored){}}
    private final CameraCaptureSession.CaptureCallback captureCallback=new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){
            if(s!=session)return;
            Long t=result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(t!=null){if(firstFrame==0){firstFrame=t;frameCount=0;}else if(t!=lastFrame){frameCount++;if(t-firstFrame>=1_000_000_000L){double fps=frameCount*1e9/(t-firstFrame);firstFrame=t;frameCount=0;
                Integer es=result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),os=result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
                actualStabilization=es==null&&os==null?"—":es!=null&&es!=0?"EIS":os!=null&&os!=0?"OIS":"ВЫКЛ";String a=actualStabilization;ui(()->listener.onFrame(fps,a));}}lastFrame=t;}
            Integer af=result.get(CaptureResult.CONTROL_AF_STATE),ae=result.get(CaptureResult.CONTROL_AE_STATE);
            if(focusActive&&af!=null&&(af!=notifiedAf||locked!=notifiedFocusLock)&&(af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED||af==CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)){notifiedAf=af;notifiedFocusLock=locked;boolean ok=af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;ui(()->listener.onFocus(ok,locked));}
            if(photoWaiting==1&&(af==null||af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED||af==CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))beginPrecapture(ae);
            else if(photoWaiting==2&&ae!=null&&(ae==CaptureResult.CONTROL_AE_STATE_PRECAPTURE||ae==CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))photoWaiting=3;
            else if(photoWaiting==3&&(ae==null||ae!=CaptureResult.CONTROL_AE_STATE_PRECAPTURE))captureStill();
        }
        @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){if(s==session)log("Capture failed reason="+f.getReason()+" frame="+f.getFrameNumber());}
    };
    void photo(int rotation){h.post(()->{
        if(state!=State.READY||video||reader==null)return;
        if(freeBytes()<64L*1024*1024){message("Недостаточно свободного места для фото");return;}
        photoSaving=false;photoRotation=CameraPolicy.mediaRotation(sensor,rotation,front);captureStarted=SystemClock.elapsedRealtime();state(State.PHOTO);
        try{if(!locked&&contains(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_AUTO)){focusActive=true;photoWaiting=1;CaptureRequest.Builder b=request(false);b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);session.capture(b.build(),captureCallback,h);}
            else beginPrecapture(null);
            int token=sessionNumber;h.postDelayed(()->{if(token==sessionNumber&&state==State.PHOTO&&photoWaiting>0)captureStill();},2500);
            h.postDelayed(()->{if(token==sessionNumber&&state==State.PHOTO&&!photoSaving){photoWaiting=0;state(State.READY);message("Камера не вернула JPEG. Попробуй Быстрое фото.");}},10000);
        }catch(Exception e){photoWaiting=0;state(State.READY);message("Фото: "+e.getMessage());}
    });}
    private void beginPrecapture(Integer ae){
        if(photoWaiting==0&&state!=State.PHOTO)return;
        if(flashAvailable&&flashMode>0&&!locked&&(flashMode==2||ae==null||ae==CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)){
            try{photoWaiting=2;CaptureRequest.Builder b=request(false);b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);session.capture(b.build(),captureCallback,h);}catch(Exception e){captureStill();}
        }else captureStill();
    }
    private void captureStill(){
        if(state!=State.PHOTO||reader==null||session==null)return;photoWaiting=0;
        try{CaptureRequest.Builder b=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());applyControls(b,true);
            session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult res){if(!locked){focusActive=false;cancelAf();}}},h);
        }catch(Exception e){state(State.READY);message("Снимок: "+e.getMessage());}
    }
    private void savePhoto(ImageReader source){
        byte[] data;int epoch=generation;
        try(Image image=source.acquireNextImage()){if(image==null)return;ByteBuffer b=image.getPlanes()[0].getBuffer();data=new byte[b.remaining()];b.get(data);}catch(Exception e){message("JPEG: "+e.getMessage());if(active)state(State.READY);return;}
        photoSaving=true;io.execute(()->{Uri uri=null;try{uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values("jpg","image/jpeg"));if(uri==null)throw new IOException("Нет файла JPEG");try(OutputStream out=context.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Нет доступа к JPEG");out.write(data);}finish(uri);Uri saved=uri;ui(()->listener.onSaved(saved,false,"Фото сохранено · DCIM/SteadyCam"));}
            catch(Exception e){delete(uri);ui(()->listener.onMessage("Ошибка сохранения фото: "+e.getMessage()));}
            finally{h.post(()->{if(epoch==generation){photoSaving=false;if(active&&state==State.PHOTO){state(State.READY);refresh();}}});}});
    }
    void startRecording(int rotation){h.post(()->{displayDegrees=rotation;sessionStabilizationOff=false;stabilizationRetry=false;startRecordingInternal();});}
    private void startRecordingInternal(){
        if(state!=State.READY||!video||device==null||selected==null||!active)return;
        if(freeBytes()<160L*1024*1024){message("Для начала видео нужно хотя бы 160 МБ свободного места");return;}
        PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_CRITICAL){message("Телефон перегрет. Дай ему остыть.");return;}
        state(State.PREPARING);message("Подготовка записи…");
        try{
            videoUri=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values("mp4","video/mp4"));
            if(videoUri==null)throw new IOException("Не удалось создать MP4");videoFd=context.getContentResolver().openFileDescriptor(videoUri,"w");if(videoFd==null)throw new IOException("Не удалось открыть MP4");
            recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(context):new MediaRecorder();
            boolean sound=audio&&context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            if(sound)recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setVideoEncoder(mime.equals("video/hevc")?MediaRecorder.VideoEncoder.HEVC:MediaRecorder.VideoEncoder.H264);
            if(sound){recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(48000);recorder.setAudioEncodingBitRate(192000);recorder.setAudioChannels(1);}
            recorder.setVideoSize(selected.size.getWidth(),selected.size.getHeight());recorder.setVideoFrameRate(selected.fps);recorder.setVideoEncodingBitRate(bitrate());
            recorder.setOrientationHint(CameraPolicy.mediaRotation(sensor,displayDegrees,front));recorder.setOutputFile(videoFd.getFileDescriptor());
            recorder.setMaxFileSize(Math.min(freeBytes()-64L*1024*1024,8L*1024*1024*1024));
            recorder.setOnErrorListener((mr,what,extra)->h.post(()->{if(recorder==mr){message("Ошибка кодировщика "+what+"/"+extra);stopRecording(true);}}));
            recorder.setOnInfoListener((mr,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)h.post(()->{if(recorder==mr){message("Достигнут предел размера файла");stopRecording(false);}});});
            recorder.prepare();configure(true);
        }catch(Exception e){cleanupRecorder(false);state(State.READY);message("Запись не запущена: "+e.getMessage());configure(false);}
    }
    int bitrate(){if(selected==null)return 12_000_000;int base=selected.size.getHeight()==2160?40_000_000:selected.size.getHeight()==1080?16_000_000:8_000_000;long value=(long)(base*(selected.fps/30.0)*(mime.equals("video/hevc")?.7:1));MediaCodecInfo.VideoCapabilities caps=codecCache.get(selected.size+"@"+selected.fps);return caps==null?(int)value:caps.getBitrateRange().clamp((int)value);}
    long freeBytes(){File f=context.getExternalFilesDir(null);return new StatFs((f==null?context.getFilesDir():f).getAbsolutePath()).getAvailableBytes();}
    long elapsed(){return publishedElapsed;}
    void tick(){h.post(()->{if(state==State.RECORDING||state==State.PAUSED)publishedElapsed=(state==State.PAUSED?pauseStart:SystemClock.elapsedRealtime())-startMs-pausedMs;else publishedElapsed=0;});}
    void pause(){h.post(()->{try{if(state==State.RECORDING){recorder.pause();pauseStart=SystemClock.elapsedRealtime();state(State.PAUSED);message("Запись на паузе");}else if(state==State.PAUSED){recorder.resume();pausedMs+=SystemClock.elapsedRealtime()-pauseStart;state(State.RECORDING);message("Запись продолжена");}}catch(Exception e){message("Пауза не поддержана кодировщиком: "+e.getMessage());}});}
    void stopRecording(){h.post(()->stopRecording(false));}
    void thermalStop(){h.post(()->{if(isBusyRecording()){message("Запись завершена из-за перегрева");stopRecording(false);}});}
    private void stopRecording(boolean error){
        if(!isBusyRecording())return;boolean started=state==State.RECORDING||state==State.PAUSED;state(State.STOPPING);++sessionNumber;closeSession();boolean saved=false;
        if(recorder!=null&&started){try{recorder.stop();saved=true;}catch(Exception e){log("Stop failed: "+e.getMessage());}}
        Uri result=videoUri;Mode used=selected;String codec=mime;cleanupRecorder(saved);
        if(saved&&result!=null){message("Видео сохранено. Проверяю частоту кадров…");io.execute(()->analyze(result,used,codec));}
        else message(error?"Кодировщик не завершил MP4; неполный файл удалён":"Слишком короткая запись: MP4 не был создан");
        publishedElapsed=0;state(active?State.READY:State.CLOSED);if(active)configure(false);
    }
    private void cleanupRecorder(boolean save){
        recordSurface=null;
        if(recorder!=null){try{recorder.reset();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}recorder=null;}
        if(videoFd!=null){try{videoFd.close();}catch(Exception ignored){}videoFd=null;}
        if(videoUri!=null){if(save)finish(videoUri);else delete(videoUri);videoUri=null;}
    }
    private void analyze(Uri uri,Mode used,String codec){
        String summary;
        MediaExtractor extractor=new MediaExtractor();
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new IOException("MP4 недоступен");extractor.setDataSource(fd.getFileDescriptor());int track=-1;
            for(int i=0;i<extractor.getTrackCount();i++){String t=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(t!=null&&t.startsWith("video/")){track=i;break;}}
            if(track<0)throw new IOException("Нет видеодорожки");extractor.selectTrack(track);long[] pts=new long[8192];int n=0;
            while(extractor.getSampleTime()>=0&&n<2_000_000){if(n==pts.length)pts=Arrays.copyOf(pts,n*2);pts[n++]=extractor.getSampleTime();if(!extractor.advance())break;}
            CameraPolicy.Stats stats=CameraPolicy.analyze(Arrays.copyOf(pts,n),used.fps);
            summary=stats.frames<2?"MP4 сохранён; запись слишком коротка для измерения FPS":String.format(Locale.US,"MP4: %.1f к/с · длинных интервалов %d%s",stats.fps,stats.longIntervals,stats.fps<used.fps*.9?" · частота ниже выбранной":"");
            prefs.edit().putString("lastAnalysis",used.label()+" / "+codec+"\n"+summary+"\nКадров: "+stats.frames+"; одинаковых PTS: "+stats.duplicateTimestamps+"\nЭто анализ временных меток MP4, без поиска одинаковых изображений.").apply();
        }catch(Exception e){summary="Видео сохранено; анализ FPS недоступен: "+e.getMessage();}
        finally{extractor.release();}
        String text=summary;ui(()->listener.onSaved(uri,true,text));
    }
    private ContentValues values(String ext,String type){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,"SC_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date())+"."+ext);v.put(MediaStore.MediaColumns.MIME_TYPE,type);v.put(MediaStore.MediaColumns.RELATIVE_PATH,"DCIM/SteadyCam");v.put(MediaStore.MediaColumns.IS_PENDING,1);return v;}
    private void finish(Uri uri){try{ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,v,null,null);}catch(Exception e){log("Publish media: "+e.getMessage());}}
    private void delete(Uri uri){if(uri!=null)try{context.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}}
    private void closeSession(){if(session!=null){try{session.stopRepeating();}catch(Exception ignored){}session.close();session=null;}if(reader!=null){reader.close();reader=null;}recordSurface=null;photoWaiting=0;}
    private void close(){++generation;++sessionNumber;opening=false;closeSession();if(device!=null){device.close();device=null;}locked=false;focusRegion=null;focusActive=false;photoSaving=false;notifiedAf=-1;state(State.CLOSED);}
    void report(java.util.function.Consumer<String> callback){h.post(()->{
        StringBuilder b=new StringBuilder("SteadyCam 13T 2.1\n").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
        try{CameraCharacteristics main=manager.getCameraCharacteristics(id==null?"0":id);
            b.append("=== КЛЮЧИ XIAOMI, КОТОРЫЕ МОЖНО ЗАДАТЬ В ЗАПРОСЕ (камера ").append(id==null?"0":id).append(") ===\n");
            for(CaptureRequest.Key<?> k:main.getAvailableCaptureRequestKeys()){String n=k.getName();if(!n.startsWith("android."))b.append(n).append('\n');}
            b.append("=== КОНЕЦ ===\n\n");}catch(Exception e){b.append("Ключи недоступны: ").append(e.getMessage()).append("\n\n");}
        try{for(String cid:manager.getCameraIdList()){
            CameraCharacteristics c=manager.getCameraCharacteristics(cid);StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            b.append(cameraName(cid)).append("\nУровень Camera2: ").append(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)).append("\nAE FPS: ").append(Arrays.toString(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))).append("\n");
            if(map!=null){Size[] ss=map.getOutputSizes(MediaRecorder.class);if(ss!=null)for(Size s:ss)if(s.getWidth()>=1280)b.append("Record ").append(s).append(" minFrame ").append(duration(map,MediaRecorder.class,s)).append(" ns\n");
                try{for(Size s:map.getHighSpeedVideoSizes())b.append("High-speed ").append(s).append(' ').append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append(" (отдельный режим, не обычная запись 60)\n");}catch(Exception ignored){}
            }
            try{b.append("Вендорные ключи запроса:\n");for(CaptureRequest.Key<?> k:c.getAvailableCaptureRequestKeys()){String n=k.getName();if(!n.startsWith("android."))b.append("  ").append(n).append('\n');}
                b.append("Вендорные ключи результата:\n");for(CaptureResult.Key<?> k:c.getAvailableCaptureResultKeys()){String n=k.getName();if(!n.startsWith("android."))b.append("  ").append(n).append('\n');}}catch(Exception ignored){}
            b.append("EIS: ").append(Arrays.toString(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES))).append(" OIS: ").append(Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION))).append("\n\n");
        }}catch(Exception e){b.append(e.getMessage());}
        b.append("Выбрана камера: ").append(id).append(" / ").append(mime).append("\nДоступные режимы:\n");for(Mode m:modes)b.append(m.label()).append("; preview ").append(m.preview).append("; AE ").append(m.range).append('\n');
        b.append("\nПочему другие режимы скрыты:\n");for(Map.Entry<String,String> e:rejected.entrySet())b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        b.append("\nПоследняя проверка видео:\n").append(prefs.getString("lastAnalysis","Видео ещё не проверялось")).append("\n\nСобытия:\n").append(eventLog);
        String text=b.toString();ui(()->callback.accept(text));
    });}
}
