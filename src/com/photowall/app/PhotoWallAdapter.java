package com.photowall.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import com.photowall.app.DiskLruCache.Snapshot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;

/**
 * 思路：
 * （1）首先在PhotoWallAdapter的构造函数中，我们初始化了LruCache类，
 * 并设置了内存缓存容量为程序最大可用内存的1/8，紧接着调用了DiskLruCache的
 * open()方法来创建实例，并设置了硬盘缓存容量为10M，
 * 这样我们就把LruCache和DiskLruCache的初始化工作完成了。
 * 
 * （2）接着在getView()方法中，我们为每个ImageView设置了一个唯一的Tag，
 * 这个Tag的作用是为了后面能够准确地找回这个ImageView，不然异步加载图片会出现乱序的情况。
 * 然后在getView()方法的最后调用了loadBitmaps()方法，加载图片的具体逻辑也就是在这里执行的了。
 * 
 * （3）进入到loadBitmaps()方法中可以看到，实现是调用了getBitmapFromMemoryCache()
 * 方法来从内存中获取缓存，如果获取到了则直接调用ImageView的setImageBitmap()方法将图片
 * 显示到界面上。如果内存中没有获取到，则开启一个BitmapWorkerTask任务来去异步加载图片。
 * 
 * （4）那么在BitmapWorkerTask的doInBackground()方法中，我们就根据图片的URL生成对应的MD5 key，
 * 然后调用DiskLruCache的get()方法来获取硬盘缓存，如果没有获取到的话则从网络上请求图片并写入硬盘缓存，
 * 接着将Bitmap对象解析出来并添加到内存缓存当中，最后将这个Bitmap对象显示到界面上，这样一个完整的流程就执行完了。
 * 
 * （5）每次加载图片的时候都优先去内存缓存当中读取，当读取不到的时候则回去硬盘缓存中读取，而如果硬盘缓存
 * 仍然读取不到的话，就从网络上请求原始数据。不管是从硬盘缓存还是从网络获取，读取到了数据之后都应该添加到
 * 内存缓存当中，这样的话我们下次再去读取图片的时候就能迅速从内存当中读取到，而如果该图片从内存中被移除了
 * 的话，那就重复再执行一遍上述流程就可以了。
 * 
 */



/**
 * GridView的适配器，负责异步从网络上下载图片展示在照片墙上。
 */
public class PhotoWallAdapter extends ArrayAdapter<String> {

	/**
	 * 记录所有正在下载或等待下载的任务。
	 */
	private Set<BitmapWorkerTask> taskCollection;

	/**
	 * 图片缓存技术的核心类，用于缓存所有下载好的图片，在程序内存达到设定值时会将最少最近使用的图片移除掉。
	 */
	private LruCache<String, Bitmap> mMemoryCache;

	/**
	 * 图片硬盘缓存核心类。
	 */
	private DiskLruCache mDiskLruCache;

	/**
	 * GridView的实例
	 */
	private GridView mPhotoWall;

	/**
	 * 记录每个子项的高度。
	 */
	private int mItemHeight = 0;

	
	/**
	 * 很多成员变量的初始化工作
	 */
	public PhotoWallAdapter(Context context, int textViewResourceId, String[] objects, GridView photoWall) {
		super(context, textViewResourceId, objects);
		mPhotoWall = photoWall;
		taskCollection = new HashSet<BitmapWorkerTask>();
		
		// 获取应用程序最大可用内存
		int maxMemory = (int) Runtime.getRuntime().maxMemory();
		// 设置图片缓存大小为程序最大可用内存的1/8
		int cacheSize = maxMemory / 8;
		
		mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
			@Override
			protected int sizeOf(String key, Bitmap bitmap) {
				return bitmap.getByteCount();
			}
		};
		
		try {
			// 获取图片缓存路径
			File cacheDir = getDiskCacheDir(context, "bitmap");
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			// 创建DiskLruCache实例，初始化缓存数据
			mDiskLruCache = DiskLruCache.open(cacheDir, getAppVersion(context), 1, 10 * 1024 * 1024);
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	

	/**
	 * 每个网格要显示出来，都会调用这个getView()方法
	 */
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		final String url = getItem(position);
		View view;
		
		if (convertView == null) {
			view = LayoutInflater.from(getContext()).inflate(R.layout.photo_layout, null);
		} 
		else {
			view = convertView;
		}
		
		final ImageView imageView = (ImageView) view.findViewById(R.id.id_photo);
		if (imageView.getLayoutParams().height != mItemHeight) {
			imageView.getLayoutParams().height = mItemHeight;
		}
		
		// 给ImageView设置一个Tag，保证异步加载图片时不会乱序
		imageView.setTag(url);
		imageView.setImageResource(R.drawable.empty_photo);
		loadBitmaps(imageView, url);
		return view;
	}
	

	/**
	 * 将一张图片存储到LruCache中。
	 * 形参 key:LruCache的键，这里传入图片的URL地址。
	 * 形参 bitmap:LruCache的键，这里传入从网络上下载的Bitmap对象。
	 */
	public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
		if (getBitmapFromMemoryCache(key) == null) {
			mMemoryCache.put(key, bitmap);
		}
	}

	/**
	 * 从LruCache中获取一张图片，如果不存在就返回null。
	 * 形参 key:LruCache的键，这里传入图片的URL地址。
	 * 返回值:对应传回键的Bitmap对象，或者返回null。
	 */
	public Bitmap getBitmapFromMemoryCache(String key) {
		return mMemoryCache.get(key);
	}

	/**
	 * 加载Bitmap对象。此方法会在LruCache中检查所有屏幕中可见的ImageView的Bitmap对象，
	 * 如果发现任何一个ImageView的Bitmap对象不在缓存中，就会开启异步线程去下载图片。
	 */
	public void loadBitmaps(ImageView imageView, String imageUrl) {
		try {
			Bitmap bitmap = getBitmapFromMemoryCache(imageUrl);
			if (bitmap == null) { //如果内存中没有那张图片
				BitmapWorkerTask task = new BitmapWorkerTask();
				taskCollection.add(task);
				task.execute(imageUrl);
			} 
			else if (imageView != null && bitmap != null) { //如果内存中有那张图片
				imageView.setImageBitmap(bitmap);
			}
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	
	/**
	 * 取消所有正在下载或等待下载的任务。
	 */
	public void cancelAllTasks() {
		if (taskCollection != null) {
			for (BitmapWorkerTask task : taskCollection) {
				task.cancel(false);
			}
		}
	}

	
	/**
	 * 根据传入的uniqueName获取硬盘缓存的路径地址。
	 */
	public File getDiskCacheDir(Context context, String uniqueName) {
		String cachePath;
		if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
			cachePath = context.getExternalCacheDir().getPath();
		} 
		else {
			cachePath = context.getCacheDir().getPath();
		}
		return new File(cachePath + File.separator + uniqueName);
	}

	
	/**
	 * 获取当前应用程序的版本号。
	 */
	public int getAppVersion(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return info.versionCode;
		} 
		catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		return 1;
	}

	
	/**
	 * 设置item子项的高度。
	 */
	public void setItemHeight(int height) {
		if (height == mItemHeight) {
			return;
		}
		mItemHeight = height;
		notifyDataSetChanged();
	}

	
	/**
	 * 使用MD5算法对传入的key进行加密并返回。
	 */
	public String hashKeyForDisk(String key) {
		String cacheKey;
		try {
			final MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(key.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(key.hashCode());
		}
		return cacheKey;
	}
	
	
	/**
	 * 将缓存记录同步到journal文件中。
	 */
	public void fluchCache() {
		if (mDiskLruCache != null) {
			try {
				mDiskLruCache.flush();
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}

	
	/**
	 * 异步下载图片的任务。
	 */
	class BitmapWorkerTask extends AsyncTask<String, Void, Bitmap> {

		//图片的URL地址
		private String imageUrl;

		/**
		 * 异步任务先执行这个方法，最终返回得到的图片
		 */
		@Override
		protected Bitmap doInBackground(String... params) {
			imageUrl = params[0];
			FileDescriptor fileDescriptor = null;
			FileInputStream fileInputStream = null;
			Snapshot snapShot = null;
			try {
				// 生成图片URL对应的key
				final String key = hashKeyForDisk(imageUrl);
				// 查找key对应的缓存
				snapShot = mDiskLruCache.get(key);
				if (snapShot == null) {
					// 如果没有找到对应的缓存，则准备从网络上请求数据，并写入缓存
					DiskLruCache.Editor editor = mDiskLruCache.edit(key);
					if (editor != null) {
						OutputStream outputStream = editor.newOutputStream(0);
						if (downloadUrlToStream(imageUrl, outputStream)) {
							editor.commit();
						} 
						else {
							editor.abort();
						}
					}
					// 缓存被写入后，再次查找key对应的缓存
					snapShot = mDiskLruCache.get(key);
				}
				if (snapShot != null) {
					fileInputStream = (FileInputStream) snapShot.getInputStream(0);
					fileDescriptor = fileInputStream.getFD();
				}
				// 将缓存数据解析成Bitmap对象
				Bitmap bitmap = null;
				if (fileDescriptor != null) {
					bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
				}
				if (bitmap != null) {
					// 将Bitmap对象添加到内存缓存当中
					addBitmapToMemoryCache(params[0], bitmap);
				}
				return bitmap;
			} 
			catch (IOException e) {
				e.printStackTrace();
			} 
			finally {
				if (fileDescriptor == null && fileInputStream != null) {
					try {
						fileInputStream.close();
					} 
					catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			return null;
		}

		
		/**
		 * 上一个方法doInBackground()的返回值，就是该方法的传入参数
		 * 即形参bitmap就是得到的那张图片
		 * 然后在这里把这张图片设置给ImageView
		 */
		@Override
		protected void onPostExecute(Bitmap bitmap) {
			super.onPostExecute(bitmap);
			// 根据Tag找到相应的ImageView控件，将下载好的图片显示出来。
			ImageView imageView = (ImageView) mPhotoWall.findViewWithTag(imageUrl);
			if (imageView != null && bitmap != null) {
				imageView.setImageBitmap(bitmap);
			}
			taskCollection.remove(this);
		}
		

		/**
		 * 建立HTTP请求，并获取Bitmap对象。
		 * 形参 imageUrl:图片的URL地址
		 * 形参 outputStream:下载完成的Bitmap隐藏在OutputStream对象里
		 *    而这个OutputStream对象是与DiskLruCache相关的，所以会自动交由
		 *    DiskLruCache进行处理，最终保存在了硬盘中，需要用get(key)才能取出
		 * 返回值:下载写入Stream成功还是失败
		 */
		private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
			HttpURLConnection urlConnection = null;
			BufferedOutputStream out = null;
			BufferedInputStream in = null;
			try {
				final URL url = new URL(urlString);
				urlConnection = (HttpURLConnection) url.openConnection();
				in = new BufferedInputStream(urlConnection.getInputStream(), 8 * 1024);
				out = new BufferedOutputStream(outputStream, 8 * 1024);
				int b;
				while ((b = in.read()) != -1) {
					out.write(b);
				}
				return true;
			} 
			catch (final IOException e) {
				e.printStackTrace();
			} 
			finally {
				if (urlConnection != null) {
					urlConnection.disconnect();
				}
				try {
					if (out != null) {
						out.close();
					}
					if (in != null) {
						in.close();
					}
				} catch (final IOException e) {
					e.printStackTrace();
				}
			}
			return false;
		}

	}

}