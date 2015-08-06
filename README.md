# PhotoWall
【Android项目】采用三级缓存机制：网络层—内存层—硬盘层，加载大量的图片，实现照片墙这样的App
<br>
<实现思路>
<br>
思路：<br>
（1）首先在PhotoWallAdapter的构造函数中，我们初始化了LruCache类，<br>
 并设置了内存缓存容量为程序最大可用内存的1/8，紧接着调用了DiskLruCache的<br>
 open()方法来创建实例，并设置了硬盘缓存容量为10M，<br>
 这样我们就把LruCache和DiskLruCache的初始化工作完成了。<br>
 <br>
（2）接着在getView()方法中，我们为每个ImageView设置了一个唯一的Tag，<br>
 这个Tag的作用是为了后面能够准确地找回这个ImageView，不然异步加载图片会出现乱序的情况。<br>
 然后在getView()方法的最后调用了loadBitmaps()方法，加载图片的具体逻辑也就是在这里执行的了。<br>
 <br>
（3）进入到loadBitmaps()方法中可以看到，实现是调用了getBitmapFromMemoryCache()<br>
 方法来从内存中获取缓存，如果获取到了则直接调用ImageView的setImageBitmap()方法将图片<br>
 显示到界面上。如果内存中没有获取到，则开启一个BitmapWorkerTask任务来去异步加载图片。<br>
<br>
（4）那么在BitmapWorkerTask的doInBackground()方法中，我们就根据图片的URL生成对应的MD5 key，<br>
 然后调用DiskLruCache的get()方法来获取硬盘缓存，如果没有获取到的话则从网络上请求图片并写入硬盘缓存，<br>
 接着将Bitmap对象解析出来并添加到内存缓存当中，最后将这个Bitmap对象显示到界面上，这样一个完整的流程就执行完了。<br>
<br>
（5）每次加载图片的时候都优先去内存缓存当中读取，当读取不到的时候则回去硬盘缓存中读取，而如果硬盘缓存<br>
 仍然读取不到的话，就从网络上请求原始数据。不管是从硬盘缓存还是从网络获取，读取到了数据之后都应该添加到<br>
 内存缓存当中，这样的话我们下次再去读取图片的时候就能迅速从内存当中读取到，而如果该图片从内存中被移除了<br>
 的话，那就重复再执行一遍上述流程就可以了。<br>
  <br>
 下面是示例动画，首先第一次打开App，会自动从网络中下载图片到硬盘上。 <br>
 然后关闭网络，退出程序，清理一下内存，再次打开App，程序就会从硬盘中读取图片，而不会从网络上读，这样就 <br>
 大大增强了图片加载的效率。 <br>
 <br>
<br>
![image](https://github.com/ZhaoYukai/PhotoWall/blob/master/%E7%A4%BA%E4%BE%8B%E5%8A%A8%E7%94%BB/jdfw.gif)
<br>

 
