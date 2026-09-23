**结论：周期性 FPS 停顿已定位到纹理发布后的客户端全量重建；MSPT 尖峰不能全部归因于纹理生成。**

spark 采样编号为 3Q7Zbl6tIa，时间为北京时间 2026 年 9 月 5 日 16:21:10.615—16:21:52.859，共 42.244 秒，4 ms 采样间隔，845 个 tick，只包含 Server thread。它没有客户端 Render thread、后台生成线程和 GPU 调用栈，也没有将每个慢 tick 单独展开，因此不能用这份平均调用树确定某一次 318 ms 尖峰的具体函数。

**1. 客户端周期性停帧：有代码与同一时间窗口日志支持。**

Tropica 的详细采样每 200 tick 发布一次颜色预览。publishColorPreview 调用 syncToAllPlayers，客户端 PlanetSyncPacket.handle 无条件依次调用行星、夜空、装饰、恒星、环世界的 refreshMeshes。

PlanetRenderer.refreshMeshes 释放所有生成纹理、清空网格和 RenderType 缓存，然后遍历全部注册星球。CPU 上的像素生成、预览噪声与排序、六面斑块拼接、资源图读取及 DynamicTexture 注册都发生在 Render thread。它不是每帧限额的上传队列。随后还会清空重建装饰粒子、恒星纹理以及环世界缓存。只改变 Tropica 的颜色也会重复处理未变化的星球。

同一个采样窗口内，可直接从“收到16颗星球同步”到“第13个普通行星网格生成完成”的日志测出以下墙钟时间：

| 客户端刷新开始 | 普通行星网格数 | 至最后一个网格日志的时间 | 证据 |
| --- | ---: | ---: | --- |
| 16:21:16.588 | 13 | 435 ms | [日志第 4362 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4362>) |
| 16:21:26.630 | 13 | 404 ms | [日志第 4379 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4379>) |
| 16:21:36.694 | 13 | 420 ms | [日志第 4396 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4396>) |
| 16:21:46.696 | 13 | 400 ms | [日志第 4413 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4413>) |

这四次合计 1,659 ms。它们是同步渲染线程处理的耗时下限，不是精确的函数 CPU 时间；尚未计入最后一条网格日志之后的装饰、恒星和环世界处理，也无法由此拆分 CPU、GC、驱动等待各自的成本。连续约 0.4 秒无法提交新帧足以造成明显停帧，与用户描述相符。实际 FPS 计数是否显示零没有在本采样中记录。

这条定时预览链路由上一轮修改新增，但仍复用了全量重建接口，是需要优先修正的设计问题。

代码入口：[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:439>)、[PlanetSyncPacket.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/network/PlanetSyncPacket.java:124>)、[PlanetRenderer.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/render/PlanetRenderer.java:144>)。预览纹理还会对 153,600 个像素建立噪声及装箱排序数组，见 [PlanetTextureLayout.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureLayout.java:121>)。

**2. 任务完成会集中发布，且最后一个任务会重复发布。**

16:20:59.745—16:20:59.948，Pelagos、Lithos、Aridia、Cryosia 在约203 ms内先后完成，分别触发全量通知，客户端连续重建四轮。此时段早于本 spark 窗口，仅作为同次游戏日志的独立证据：[日志第 4258 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4258>)。

16:21:58.158，Tropica 完成后同一毫秒出现两条服务器同步日志，随后客户端在16:21:58.184与16:21:58.647分别处理一轮，普通行星网格部分至少耗时276 ms和252 ms。此时段晚于采样结束5秒多，不能当作调用树已经采到的事件：[日志第 4491 行](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/latest-log-snapshot.log:4491>)。

原因是 SurfaceMapJob.commit 本身同步一次，最后一个任务的 completion.complete 又触发 prewarmGeneratedTextures 的 whenComplete 再同步一次。没有合并通知、变更版本去重或只更新一颗行星的协议。

代码入口：[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:722>)、[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:203>)、[PlanetRegistry.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetRegistry.java:837>)。

**3. 当前生成队列限制的是单颗星球的区块次数，不是全局耗时。**

onServerTick 依次处理每个任务，每颗星球最多调用10次 processOneChunk，缺少所有星球共享的毫秒预算、全局在途区块上限和轮转让步。多个任务同时完成时，采样、最终整幅RGB565编码、数组复制及发布可落在同一个tick内。

ProtoChunk 构造发生在 supplyAsync 之前。普通地形的后台工作使用默认 CompletableFuture 线程池；特征生成采用 thenApply，并非所有延续步骤都强制离开调用线程。对于已经完成的future，调用位置也会影响执行线程。现有代码仅在future.isDone后join，因此这里没有发现“服务器盲等未完成future”的证据。

另外，Minecraft 的 BlockableEventLoop.execute 在当前就是服务端线程时直接执行 task.run；写成 server.execute 并不自动意味着分摊到后续tick。因此现有设计确实允许突发开销，但不能把这个可能性当作本采样318 ms尖峰的直接证明。

代码入口：[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:198>)、[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:481>)、[PlanetTextureGenerator.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/src/main/java/world/landfall/deepspace/planet/PlanetTextureGenerator.java:546>)。本地Minecraft依赖源码核对：[BlockableEventLoop.java](<C:/Program Files (x86)/360/360PT/mc/modding/DeepSpace_versionK/build/tmp/spark-3Q7Zbl6tIa/BlockableEventLoop.java:94>)。

**4. spark中服务端的主要工作还有物理模拟及区块处理。**

元数据中的MSPT中位数为9.6943 ms，95分位58.3024 ms，最大318.1928 ms，符合平时较低、偶发超时的表现。tickServer的采样累计为9,132 ms，其余时间主要位于等待下一tick的路径；waitUntilNextTick累计33,016 ms，其中也包含执行排队任务，不应把整段都标成CPU空转或死锁。

| 调用 | 累计采样时间 | 占整个42.244秒采样 | 占tickServer采样 |
| --- | ---: | ---: | ---: |
| Sable Rapier3D.step | 1,984 ms | 4.70% | 21.73% |
| ChunkMap.processUnloads | 1,324 ms | 3.13% | 14.50% |
| ServerChunkCache.tickChunks | 1,612 ms | 3.82% | 17.65% |
| PlanetTextureGenerator.onServerTick | 48 ms | 0.11% | 0.53% |

这些是采样估计、包含子调用的时间，不能相加得出一次慢tick的构成。物理step、区块卸载/保存是服务端后续排查方向；本调用树既不支持“纹理采样占满服务端”的判断，也不排除采样区间之外的集中提交或未采样后台线程造成争用。

**5. 内存压力是可能的放大因素，尚无逐尖峰归因证据。**

采样快照显示物理内存使用约15.09/15.70 GiB（96.12%）；Java堆使用约4.13 GiB、上限8 GiB。G1 Young累计平均收集时间约34.37 ms，但它是JVM累计统计，不是这42秒内每次停顿的时间线。频繁全量纹理重建的大数组、排序装箱和本地图像分配会增加分配压力；是否发生了特定GC或换页导致的318 ms尖峰，仍需对应时间的GC/客户端线程数据，不能只凭内存占用断言。

**建议按以下顺序修正：**

1. 为纹理变化增加按行星ID及版本的增量更新，只修改变化的纹理/颜色；布局没有变化时保留网格、RenderType、恒星、装饰和环世界资源。预览更新不再触发全局refreshMeshes。
2. 对预览和完成通知合并去重。最后一个任务完成只发布一次；同一批多行星完成合成一批变更。
3. 将像素生成、斑块拼接和资源读取放入有并发上限的后台任务。渲染线程采用有时间/数量预算的上传队列，六面准备好后一起替换，准备期间继续显示旧纹理。
4. 将服务端限制改为所有任务共享的时间预算及在途工作量上限，轮转处理。预算用尽就留到下一tick；最终整图编码/复制也需要分阶段处理，避免完成时集中执行。
5. 单独分析Sable物理和区块卸载/保存的慢tick。需要定位具体MSPT尖峰时应采慢tick及相关后台线程；需要拆分FPS停帧成本时应包含客户端Render thread。现有数据足够确认全量重建缺陷，但不足以给每次尖峰贴上唯一原因。

本轮完成原因核验及证据整理，未修改模组源代码，未启动Minecraft或运行模组JAR。
