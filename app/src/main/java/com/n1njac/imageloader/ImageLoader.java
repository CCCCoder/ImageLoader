package com.n1njac.imageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import com.jakewharton.disklrucache.DiskLruCache;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by huanglei on 16/10/15.
 */

public class ImageLoader {

    private static final String TAG = "ImageLoader";

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final long DISK_CACHE_SIZE = 1024 * 1024 *50;
    private static final int TAG_KEY_URI = R.id.tag_first;
    private static final int MESSAGE_POST_RESULT = 1;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;

    private boolean mIsDiskLruCacheCreated = false;

    //线程池的实现
    //线程工厂类
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader #" + mCount.getAndIncrement());
        }
    };

    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    //构造线程池
    public static final Executor THREAD_POOL_EXECUTOR
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE,
            TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);

    //运行在主线程，用来更改ui
    //每次设置图片之前都检查他的url有没有改变，
    // 如果改变就不再给其设置图片，这样就解决了图片错误的问题
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG, "set image bitmap,but url has changed, ignored!");
            }

        }
    };

    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    public ImageLoader(Context context) {

        //创建LruCache
        mContext = context.getApplicationContext();
        ////获取系统分配给每个应用程序的最大内存。单位：KB
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            //计算缓存对象的大小
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                //创建DiskLruCache
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }


    //创建一个新的ImageLoader实例
    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    //添加到内存缓存
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if (mMemoryCache.get(key) == null){
            mMemoryCache.put(key, bitmap);
        }
    }

    public void bindBitmap(final String uri, final ImageView imageView){
        bindBitmap(uri,imageView,0,0);
    }

    //加载bitmap从内存缓存或者磁盘缓存或者网络，并且让imageView和bitmap绑定
    //异步加载图片
    //异步接口的设计
    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI,uri);
        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        //使用线程池提供imageloader的并发能力
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    bitmap = loadBitmap(uri,reqWidth,reqHeight);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (bitmap != null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result).sendToTarget();
                }
            }
        };
        //线程池执行
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);

    }

    //同步接口的实现即已同步的方式加载图片
    //以同步接口的方式去加载图片，走的是内存，磁盘接着网络的获取渠道
    //同步接口的不能再主线程执行，需要在外部的线程调用
    public Bitmap loadBitmap(String uri,int reqWidth,int reqHeight) throws IOException {
        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            Log.i(TAG,"loadBitmapFromMemoryCache,url:"+uri);
            return bitmap;
        }
        bitmap = loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
        if (bitmap != null){
            Log.i(TAG,"loadBitmapFromDiskCache,url:" + uri);
            return bitmap;
        }
        //这里是不是很奇怪？为什么在磁盘中加载图片没有加载到的时候要调用添加图片到磁盘缓存的方法？
        //这是因为：注意看loadBitmapFromHttp的方法，最后return loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
        //这就是说如果磁盘缓存中没有该图片的话，先将图片下载到文件系统并添加到磁盘缓存中，在调用磁盘加载的方法（loadBitmapFromDiskCache）
        // 加载图片；
        //注意这里，这里是磁盘缓存已经创建的情况下。如果磁盘缓存没有创建，就调用downloadBitmapFromUrl这个方法从网上下载
        bitmap = loadBitmapFromHttp(uri,reqWidth,reqHeight);
        Log.i(TAG,"loadBitmapFromHttp,url:" + uri);


        if (bitmap == null && !mIsDiskLruCacheCreated){
            Log.w(TAG,"DiskLruCache is not create");
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    // 磁盘缓存的添加操作
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight)
    throws IOException{
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread.");
        }
        if (mDiskLruCache == null){
            return null;
        }
        //url中可能有特殊字符影响使用，一般采用url的md5值作为key
        String key = hashKeyFromUrl(url);
        //Editor表示一个缓存对象的编辑对象，DiskLruCache不允许同时编辑一个缓存对象
        //下面几步操作，图片已经被正确的写入到文件系统了。
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToSteam(url,outputStream)) {
                editor.commit();
            }else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url,reqWidth,reqHeight);

    }

    //磁盘缓存的读取操作
    private Bitmap loadBitmapFromDiskCache(String uri, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from ui Thread,its not recommended");
        }
        if (mDiskLruCache == null){
            return null;
        }
        Bitmap bitmap = null;
        String key = hashKeyFromUrl(uri);
        DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
        //为了避免OOM，一般不建议加载原始的图片，所以采用压缩类
        FileInputStream inputSteam = (FileInputStream) snapShot.getInputStream(DISK_CACHE_INDEX);
        FileDescriptor fileDescriptor = inputSteam.getFD();
        bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor,reqWidth,reqHeight);
        //如果加载原图，就是下面的方法
        //Bitmap b = BitmapFactory.decodeStream(inputSteam);
        //加载出来后，添加到内存缓存中，下次加载的时候直接从内存中加载。
        if (bitmap != null){
            addBitmapToMemoryCache(key,bitmap);
        }
        return bitmap;
    }
    //这个方法是用在磁盘缓存的内部，磁盘创建的情况下，磁盘缓存没有图片时，从网上下载的方法。
    //downloadBitmapFromUrl这个方法是直接从网上下载然后直接加载到image view
    //outputStream:当从网上下载图片时，图片就可以通过这个文件输出流写入到文件系统上。
    private boolean downloadUrlToSteam(String urlString, OutputStream outputStream) {
        HttpURLConnection conn = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(conn.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);

            int b;
            while ((b = in.read()) != -1){
                out.write(b);
            }
            return true;

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            Log.e(TAG,"downloadBitmap failed");
        }finally {
            if (conn != null){
                conn.disconnect();
            }
            try {
                in.close();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    //从内存中加载图片
    private Bitmap loadBitmapFromMemoryCache(String uri) {
        final String key = hashKeyFromUrl(uri);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    //从内存缓存中拿到图片
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    //将图片的url转换成key，因为图片的url中很有可能有特殊字符，采用url的md5值作为key
    private String hashKeyFromUrl(String uri) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(uri.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());

        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(uri.hashCode());
        }
        return cacheKey;
    }

    //字节转换为十六进制
    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            //转换成十六进制字符串
            String hex = Integer.toHexString(0xFF & digest[i]);
            if (hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    //直接在网上下载图片并加载到imageview
    private Bitmap downloadBitmapFromUrl(String uri) {
        Bitmap bitmap = null;
        HttpURLConnection conn = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(uri);
            conn = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(conn.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (final IOException e) {
            Log.e(TAG,"Error in downloadBitmap:"+ e );
        }finally {
            if (conn != null){
                conn.disconnect();
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }

    //磁盘缓存的路径
    public File getDiskCacheDir(Context context, String uniqueName) {
        //判断SD卡是否存在，并且是否具有读写权限
        boolean externalStorageAvailable = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }


    private long getUsableSpace(File path) {
        return path.getUsableSpace();

    }

    private static class LoaderResult{
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}
