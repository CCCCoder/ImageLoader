package com.n1njac.imageloader;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

import static com.n1njac.imageloader.MainActivity.mCanGetBitmapFromNetWork;
import static com.n1njac.imageloader.MainActivity.mIsGridViewIdle;

/**
 * Created by huanglei on 16/10/16.
 */

public class ImageAdapter extends BaseAdapter {

    private List<String > mUrlList;
    private LayoutInflater inflater;
    private ImageLoader mImageLoader;



    public ImageAdapter(Context context,List<String > mUrlList){
        this.mUrlList = mUrlList;
        inflater = LayoutInflater.from(context);
        mImageLoader = new ImageLoader(context);
    }


    @Override
    public int getCount() {
        return mUrlList.size();
    }

    @Override
    public Object getItem(int position) {
        return mUrlList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder viewHolder = null;
        if (convertView == null){
            viewHolder = new ViewHolder();
            convertView = inflater.inflate(R.layout.gridview_item,null);
            viewHolder.imageView = (ImageView) convertView.findViewById(R.id.image_1);
            convertView.setTag(viewHolder);
        }else {
            viewHolder = (ViewHolder) convertView.getTag();
        }
        final String tag = (String) viewHolder.imageView.getTag();
        final String url = (String) getItem(position);
        //防止错位
        if (!url.equals(tag)){
            viewHolder.imageView.setImageResource(R.mipmap.ic_launcher);
        }
        if (mIsGridViewIdle && mCanGetBitmapFromNetWork){
            viewHolder.imageView.setTag(url);
            mImageLoader.bindBitmap(url,viewHolder.imageView,100,100);
        }

        return convertView;
    }
    public class ViewHolder{
        ImageView imageView;
    }

}
