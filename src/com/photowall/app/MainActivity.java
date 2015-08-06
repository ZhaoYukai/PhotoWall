package com.photowall.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.GridView;

/**
 * 照片墙主活动，使用GridView展示照片墙。
 */
public class MainActivity extends Activity {

	//用于展示照片墙的GridView
	private GridView mPhotoWall = null;

	//GridView的适配器
	private PhotoWallAdapter mAdapter = null;
	
	private Context mContext = null;

	private int mImageThumbSize;
	private int mImageThumbSpacing;

	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		mImageThumbSize = getResources().getDimensionPixelSize(R.dimen.image_thumbnail_size);
		mImageThumbSpacing = getResources().getDimensionPixelSize(R.dimen.image_thumbnail_spacing);
		mPhotoWall = (GridView) findViewById(R.id.id_photo_wall);
		mAdapter = new PhotoWallAdapter(this, 0, Images.imageThumbUrls, mPhotoWall);
		mContext = MainActivity.this;
		
		mPhotoWall.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				Toast.makeText(mContext , position + "" , Toast.LENGTH_SHORT).show();
			}
		});
		
		mPhotoWall.setAdapter(mAdapter);
		mPhotoWall.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
					@SuppressWarnings("deprecation")
					@Override
					public void onGlobalLayout() {
						final int numColumns = (int) Math.floor(mPhotoWall.getWidth() / (mImageThumbSize + mImageThumbSpacing));
						if (numColumns > 0) {
							int columnWidth = (mPhotoWall.getWidth() / numColumns) - mImageThumbSpacing;
							mAdapter.setItemHeight(columnWidth);
							mPhotoWall.getViewTreeObserver().removeGlobalOnLayoutListener(this);
						}
					}
				});
	}
	
	
	
	@Override
	protected void onPause() {
		super.onPause();
		mAdapter.fluchCache();
	}
	
	

	@Override
	protected void onDestroy() {
		super.onDestroy();
		// 退出程序时结束所有的下载任务
		mAdapter.cancelAllTasks();
	}
	
	

}
