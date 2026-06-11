# 照片转线稿涂色功能 - 技术方案

## 一、需求概述

在现有"宝贝画画"儿童绘图应用基础上，新增以下功能：
1. 用户从相册选择照片或使用相机拍摄照片
2. 将照片自动转换为黑白线稿
3. 线稿载入画板，用户可在线稿上涂色
4. 利用已有的区域约束涂色功能（Two-Pass CCL 区域分析）

---

## 二、技术调研：图片转线稿算法对比

### 2.1 候选算法总览

| 算法 | 类型 | 模型/库大小 | 推理/处理时间 (512px) | 线稿质量 | 部署难度 |
|------|------|-------------|----------------------|---------|---------|
| ControlNet Lineart Preprocessor | 神经网络 | ~6MB 模型 + ~30MB ONNX Runtime | 200-400ms (CPU) | 很好 | 高 |
| PiDiNet-tiny-l | 神经网络 | ~1.2MB 模型 + ~30MB ONNX Runtime | 100-200ms (CPU) | 好 | 中 |
| LDC (Lightweight Dense CNN) | 神经网络 | ~2.7MB 模型 + ~30MB ONNX Runtime | 150-300ms (CPU) | 很好 | 中 |
| XDoG (扩展高斯差分) | 经典 CV | 0（纯算法实现） | 50-100ms | 好 | 低 |
| Canny 边缘检测 | 经典 CV | 0（纯算法实现） | 15-30ms | 中等 | 低 |
| 自适应阈值 (Sauvola/Bradley) | 经典 CV | 0（纯算法实现） | 10-50ms | 差 | 低 |

### 2.2 各算法详细分析

#### (A) ControlNet Lineart Preprocessor（推荐主方案）

ControlNet v1.1 的 lineart 预处理器是一个独立的 CNN 编码器-解码器网络（来自 lllyasviel/ControlNet-v1-1 的 `controlnet_aux` 库）。它不需要完整的 Stable Diffusion 管线，仅预处理器本身约 5-7MB。

**优势：**
- 语义理解能力强，能区分有意义的轮廓和无用纹理
- 输出的线条干净、连续，非常适合涂色
- 对不同场景（人物、风景、物品）泛化性好

**劣势：**
- 需要 ONNX Runtime Android 依赖（约 30MB，裁剪后）
- PyTorch → ONNX 转换需要处理 Resize 算子兼容性
- 没有官方 TFLite 版本，转换路径较长
- 对低端设备不够友好

**部署路径：** PyTorch (.pth) → ONNX (.onnx) → ONNX Runtime Android 推理

#### (B) PiDiNet-tiny-l（轻量备选方案）

Pixel Difference Networks (ICCV 2021)，专门设计用于高效边缘检测。tiny-l 变体仅 304K 参数、1.2MB 模型。

**优势：**
- 极小模型，ONNX 转换简单（纯标准 Conv2d + ReLU 算子）
- 推理速度快，中端手机 CPU 约 100-200ms
- APK 增量仅约 1-2MB（模型文件）

**劣势：**
- 输出更偏"技术性"边缘而非艺术线稿
- 需要后处理（形态学操作）才能达到涂色页质量
- 对复杂场景的语义理解不如 ControlNet preprocessor

#### (C) XDoG（经典方案 / Fallback）

扩展高斯差分算法 (Winnemöller, 2012)，经典的非真实感渲染线稿提取方法。

**处理管线：**
```
输入照片 → 双边滤波(3-5次) → 灰度转换 → XDoG边缘检测 → 形态学闭运算 → 反色 → 白底黑线线稿
```

**优势：**
- 零外部依赖，纯 Kotlin 实现
- 处理速度快（50-100ms for 1024px）
- 参数可调（线条密度、粗细、细节级别）
- 不存在模型幻觉问题

**劣势：**
- 纹理噪声会被误检为边缘（衣服花纹、皮肤毛孔）
- 对复杂照片效果不如 ML 方案
- 需要用户手动调参

### 2.3 最终推荐方案：双引擎架构

考虑到线稿质量要求和实际部署可行性，推荐采用 **"ML 主引擎 + 经典 Fallback"** 的双引擎架构：

```
┌─────────────────────────────────────────────┐
│             照片输入                          │
│          (相册 / 相机)                        │
└──────────────┬──────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│         图像预处理（共用）                      │
│  - 降采样到工作分辨率(512-768px)               │
│  - 图像增强（对比度/亮度自适应）                │
└──────────────┬───────────────────────────────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌─────────────┐  ┌──────────────┐
│ ML 引擎      │  │ 经典引擎      │
│ (主方案)     │  │ (Fallback)   │
│             │  │              │
│ ControlNet  │  │ XDoG 管线    │
│ Lineart     │  │              │
│ Preprocessor│  │ 双边滤波     │
│ (ONNX)      │  │ + XDoG       │
│             │  │ + 形态学     │
└──────┬──────┘  └──────┬───────┘
       │               │
       └───────┬───────┘
               ▼
┌──────────────────────────────────────────────┐
│         后处理（共用）                         │
│  - 形态学闭运算（修复断线）                    │
│  - 轮廓过滤（去除小噪声碎片）                  │
│  - 升采样到画布分辨率                         │
│  - 输出：白底黑线线稿 Bitmap                  │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│    DrawingView 区域分析（已有功能）             │
│    - 梯度感知边界检测                          │
│    - 形态学闭运算                              │
│    - Two-Pass CCL 区域标记                     │
│    → 区域掩码 → 区域约束涂色                   │
└──────────────────────────────────────────────┘
```

**选择理由：**
1. ML 引擎提供最高质量的线稿，满足用户对高质量的需求
2. 经典引擎作为 Fallback，在 ONNX 模型加载失败或设备不支持时使用
3. 共享前后处理模块，减少代码冗余
4. 未来可方便地添加更多引擎（如 TFLite 模型）

---

## 三、ML 推理框架选型

### 3.1 ONNX Runtime vs TFLite/LiteRT

| 维度 | ONNX Runtime Android | TFLite / LiteRT |
|------|---------------------|-----------------|
| 运行时大小 | ~30MB（裁剪后） | ~3-5MB |
| PyTorch 模型转换难度 | 中等（ONNX 导出成熟） | 困难（Resize 算子兼容问题） |
| GPU/NPU 加速 | NNAPI 代理 | GPU Delegate（更好） |
| 社区生态 | 活跃，微软维护 | Google 维护 |
| ControlNet 预处理器支持 | 较好 | 无预转换模型 |

**推荐：ONNX Runtime Android**

理由：
- ControlNet lineart preprocessor 可以直接从 PyTorch 导出 ONNX
- TFLite 转换路径不成熟，Resize 算子是主要障碍
- ONNX Runtime 的 NNAPI 代理可以利用设备的 NPU/GPU 加速
- 虽然运行时较大（~30MB），但对现代手机可以接受

### 3.2 模型转换流程

```
Step 1: PyTorch → ONNX
  工具: torch.onnx.export()
  要点: 固定输入 512x512, opset_version=13

Step 2: ONNX 验证
  工具: onnx.checker, onnxruntime (Python)
  验证: 输出与 PyTorch 一致

Step 3: 集成到 Android
  放置: app/src/main/assets/lineart.onnx
  依赖: com.microsoft.onnxruntime:onnxruntime-android:1.22.0
  推理: OrtEnvironment + OrtSession
```

---

## 四、照片采集方案

### 4.1 相册选择

使用 Android Photo Picker (`ActivityResultContracts.PickVisualMedia`)：
- Android 13+：使用系统照片选择器（无需权限）
- Android 9-12：自动回退到 `ACTION_OPEN_DOCUMENT`（无需权限）
- 无需额外的 `READ_EXTERNAL_STORAGE` 权限

### 4.2 相机拍摄

使用 `ActivityResultContracts.TakePicture()`（Intent 方式）：
- 调用系统相机 App，无需 CAMERA 权限
- 通过 FileProvider 传递 URI，获取全分辨率照片
- 无需 CameraX 集成，代码量极少（~20行）

### 4.3 图像加载

使用 `ImageDecoder` (API 28+, 与 minSdk=28 兼容)：
- 自动降采样，避免 OOM
- `MEMORY_POLICY_LOW_RAM` 优化内存使用
- 支持 JPEG/PNG/WebP 格式

---

## 五、软件模块分解

### 5.1 新增模块列表

```
com.zucky.drawing/
├── photo/                          # 新模块：照片导入
│   ├── PhotoPickerHelper.kt       # 相册选择 & 相机拍摄封装
│   ├── ImageLoader.kt             # 图像加载 & 降采样
│   └── file_paths.xml             # FileProvider 路径配置
│
├── lineart/                        # 新模块：线稿转换引擎
│   ├── LineArtEngine.kt           # 引擎接口（统一抽象）
│   ├── MlLineArtEngine.kt         # ML 引擎：ONNX Runtime 推理
│   ├── XDoGLineArtEngine.kt       # 经典引擎：XDoG 管线
│   ├── ImagePreprocessor.kt       # 共用预处理（降采样、增强）
│   └── LineArtPostprocessor.kt    # 共用后处理（形态学、过滤）
│
├── gallery/                        # 修改：添加照片导入入口
│   └── GalleryActivity.kt         # 新增"导入照片"按钮 & 来源选择对话框
│
├── drawing/                        # 修改：支持从 URI 加载线稿
│   ├── DrawingActivity.kt         # 新增 photo URI 处理分支
│   └── DrawingView.kt             # 无需修改（已有区域分析）
│
├── model/                          # 修改：添加照片模板源类型
│   └── Template.kt                # 新增 TemplateSource.PhotoUri
│
└── ui/                             # 新增：线稿转换进度界面
    └── LineArtProgressDialog.kt   # 转换进度对话框
```

### 5.2 模块详细设计

#### (1) PhotoPickerHelper

```kotlin
// 封装照片选择 & 相机拍摄的 ActivityResult 回调
class PhotoPickerHelper(activity: ComponentActivity) {
    // 相册选择 launcher
    val pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    // 相机拍摄 launcher
    val takePictureLauncher: ActivityResultLauncher<Uri>
    // 临时照片 URI
    var cameraPhotoUri: Uri?

    fun pickFromGallery()      // 启动相册选择
    fun takePhoto(): Uri       // 启动相机拍摄，返回临时文件 URI
    fun showSourceDialog()     // 显示"相册/相机"选择对话框
}
```

#### (2) ImageLoader

```kotlin
// 高效加载图像，避免 OOM
object ImageLoader {
    fun decodeBitmapFromUri(
        resolver: ContentResolver, uri: Uri,
        targetWidth: Int, targetHeight: Int
    ): Bitmap?
    // 使用 ImageDecoder (API 28+) 或 BitmapFactory
    // 自动计算 inSampleSize 降采样
}
```

#### (3) LineArtEngine (接口)

```kotlin
// 统一线稿转换引擎接口
interface LineArtEngine {
    val name: String
    suspend fun convert(
        inputBitmap: Bitmap,
        onProgress: (Float) -> Unit
    ): Bitmap  // 返回白底黑线线稿
    fun isAvailable(): Boolean
}
```

#### (4) MlLineArtEngine

```kotlin
// 基于 ONNX Runtime 的 ML 线稿引擎
class MlLineArtEngine(context: Context) : LineArtEngine {
    // 加载 assets/lineart.onnx 模型
    // 预处理: resize 512x512, normalize [0,1], NCHW
    // 推理: OrtSession.run()
    // 后处理: sigmoid → threshold → binary → morphological cleanup
}
```

#### (5) XDoGLineArtEngine

```kotlin
// 纯算法 XDoG 线稿引擎（Fallback）
class XDoGLineArtEngine : LineArtEngine {
    // 管线: bilateral filter → grayscale → XDoG → morphological close → invert
    // 参数: sigma=1.0, k=1.6, epsilon=0.03, phi=15
}
```

#### (6) ImagePreprocessor / LineArtPostprocessor

```kotlin
// 共用图像预处理
object ImagePreprocessor {
    fun downsample(input: Bitmap, maxDim: Int): Bitmap
    fun enhanceContrast(input: Bitmap): Bitmap
}

// 共用后处理
object LineArtPostprocessor {
    fun morphologicalClose(input: Bitmap, kernelSize: Int): Bitmap
    fun removeSmallContours(input: Bitmap, minArea: Int): Bitmap
    fun upscale(input: Bitmap, targetW: Int, targetH: Int): Bitmap
}
```

#### (7) Template.kt 扩展

```kotlin
sealed class TemplateSource {
    data class BuiltIn(val generatorId: Int) : TemplateSource()
    data class AssetImage(val assetPath: String) : TemplateSource()
    // 新增：从照片 URI 生成线稿
    data class PhotoUri(val uri: Uri) : TemplateSource()
}
```

---

## 六、用户交互流程

```
GalleryActivity（素材选择界面）
    │
    ├── [原有] 内置模板列表（12个）
    ├── [原有] assets 模板列表
    │
    └── [新增] "导入照片" 按钮
              │
              ▼
        选择来源对话框
        ┌──────────────┐
        │ 📷 拍照       │
        │ 🖼️ 从相册选择 │
        └──────────────┘
              │
              ▼
        获取照片 URI
              │
              ▼
    ┌─────────────────────────┐
    │  线稿转换处理界面        │
    │                         │
    │  ┌─────────────────┐   │
    │  │ 原始照片预览     │   │
    │  │                 │   │
    │  │ [ML引擎] 转换中… │   │
    │  │ ████████░░ 75%   │   │
    │  └─────────────────┘   │
    │                         │
    │  [取消]  [重新选择]      │
    └─────────────────────────┘
              │
              ▼ (转换完成)
    ┌─────────────────────────┐
    │  预览 & 调整             │
    │                         │
    │  ┌─────────────────┐   │
    │  │ 线稿预览         │   │
    │  │                 │   │
    │  │ [细节级别: ━━━●] │   │
    │  └─────────────────┘   │
    │                         │
    │  [重新转换] [开始涂色 →] │
    └─────────────────────────┘
              │
              ▼
    DrawingActivity（绘图界面）
    - 线稿作为 templateBitmap
    - 自动区域分析（已有）
    - 区域约束涂色（已有）
```

---

## 七、依赖变更

### 7.1 新增依赖

```kotlin
// build.gradle.kts
dependencies {
    // ONNX Runtime for Android (ML 推理)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    
    // Photo Picker 向后兼容
    // (已有 activity-ktx:1.9.3，需升级到 1.10+ 以获得 PhotoPicker 回退支持)
    implementation("androidx.activity:activity-ktx:1.10.1")
}
```

### 7.2 新增资源文件

```
app/src/main/
├── assets/
│   └── lineart.onnx              # ML 模型文件 (~6MB)
├── res/
│   ├── xml/file_paths.xml        # FileProvider 路径配置
│   ├── layout/
│   │   └── dialog_lineart_preview.xml  # 线稿预览/调整对话框
│   └── values/
│       └── strings.xml           # 新增字符串资源
└── AndroidManifest.xml           # 新增 FileProvider 配置
```

### 7.3 APK 体积影响估算

| 组件 | 大小 |
|------|------|
| ONNX Runtime Android AAR（裁剪后） | ~30 MB |
| lineart.onnx 模型 | ~6 MB |
| 新增 Kotlin 代码 | ~0.1 MB |
| 新增资源文件 | ~0.05 MB |
| **总计** | **~36 MB** |

---

## 八、风险 & 缓解

| 风险 | 影响 | 缓解方案 |
|------|------|---------|
| ONNX 模型转换失败 | ML 引擎不可用 | XDoG 引擎作为 Fallback |
| 低端设备推理慢 | 用户等待时间长 | 降采样到 384px + 进度条 + NNAPI 加速 |
| ONNX Runtime 30MB 太大 | APK 体积膨胀 | 考虑未来迁移 LiteRT（3-5MB） |
| 线稿区域分析失败（断线） | 涂色溢出 | 已有形态学闭运算修复断线 |
| 照片 URI 权限丢失 | 无法加载照片 | 持久化 URI 权限 + 异常处理 |

---

## 九、实施计划

| 阶段 | 内容 | 预估工时 |
|------|------|---------|
| Phase 1 | 照片采集模块（相册+相机） | 2h |
| Phase 2 | XDoG 经典引擎（可独立验证） | 4h |
| Phase 3 | ML 引擎（ONNX 转换+推理集成） | 8h |
| Phase 4 | 线稿预览 & 参数调整 UI | 3h |
| Phase 5 | DrawingActivity 集成 + 区域分析 | 2h |
| Phase 6 | QA 测试 & 优化 | 4h |
| **总计** | | **~23h** |
