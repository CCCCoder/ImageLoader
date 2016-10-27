package com.n1njac.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by huanglei on 16/10/15.
 */

public class ImageResizer {
    private static final String TAG = "ImageResizer";

    public Bitmap decodeSampledFromResource(Resources res,int resId,int reqWidth,int reqHeight){
        BitmapFactory.Options options = new BitmapFactory.Options();
        //让解析方法禁止为bitmap分配内存，返回值也不再是一个Bitmap对象，而是null
        //虽然Bitmap是null了，但是BitmapFactory.Options的outWidth、outHeight和outMimeType属性都会被赋值
        options.inJustDecodeBounds  = true;
        BitmapFactory.decodeResource(res,resId,options);
        //计算InSampleSize
        options.inSampleSize = CalcuteInSampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res,resId,options);

    }

    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd,int reqWidth,int reqHeight){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds  = true;
        BitmapFactory.decodeFileDescriptor(fd,null,options);
        //计算InSampleSize
        options.inSampleSize = CalcuteInSampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd,null,options);
    }

    //根据实际尺寸计算压缩的samplesize
    private int CalcuteInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        if (reqWidth == 0 || reqHeight == 0){
            return 1;
        }
        //图片的真实宽高度
        int height = options.outHeight;
        int width = options.outWidth;
        Log.i(TAG,"真实尺寸：" +"height:"+ height +"width:"+width );
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth){
            int halfHeight = height/2;
            int halfWidth = width/2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        Log.i(TAG,"sampleSize:"+inSampleSize);
        return inSampleSize;

    }
}
