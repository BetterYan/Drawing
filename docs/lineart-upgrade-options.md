# 线稿提取模块升级方案

> 本文档汇总了当前 OpenCV 线稿提取引擎的 3 个升级方向，按实现难度从低到高排列。
> 适用于「宝贝画画」Android 应用（minSdk=28，OpenCV 4.11.0 已集成）。

---

## 背景

当前线稿提取管线基于 OpenCV 传统算法：

```
照片 → 降采样 → 灰度 → CLAHE → 双边滤波
    → 自适应阈值(轮廓) + Canny(边缘) → 加权融合
    → 二值化 → 形态学闭/开运算 → 轮廓过滤
    → 反色 → 白底黑线线稿
```

该方案对**高对比度、简单背景**的照片效果尚可，但对以下场景表现不佳：
- 纹理噪声（衣服花纹、皮肤毛孔、背景细节被误检为边缘）
- 复杂光照（阴影被误认为轮廓）
- 毛发、植物等高频细节（产生碎片化线条）

---

## 选项 1：优化现有 OpenCV 管线参数（已完成）

### 目标
在不引入任何新依赖的前提下，通过算法参数调优和管线改进，显著提升当前线稿质量。

### 已实施的优化点

| # | 优化项 | 原方案 | 优化后 | 效果 |
|---|--------|--------|--------|------|
| 1 | 颜色通道 | `COLOR_BGR2GRAY` | `COLOR_RGB2GRAY` | 修复 Android Bitmap 通道顺序 |
| 2 | 对比度增强 | 无 | 增加 **CLAHE**（自适应直方图均衡化） | 提升低对比度照片的轮廓清晰度 |
| 3 | 降采样插值 | `Bitmap.createScaledBitmap`（双线性） | OpenCV `INTER_AREA` | 抗混叠，减少虚假边缘 |
| 4 | 双边滤波参数 | sigma 20~80（范围过宽） | sigma 30~60（收紧） | 减少过度平滑 |
| 5 | 轮廓+边缘融合 | `bitwise_or`（噪声放大） | `addWeighted(0.6, 0.4)` + 二值化 | 降低独立噪声叠加 |
| 6 | 轮廓检索模式 | `RETR_EXTERNAL`（仅外层） | `RETR_TREE`（保留层次） | 保留眼睛、嘴等内部细节 |
| 7 | 输出纯度 | 直接反色 | 反色前 `threshold(127)` | 确保纯黑白输出 |

### 进一步优化空间

如果效果仍不理想，可继续微调以下参数：

```kotlin
// 1. 调整 CLAHE 强度（当前 clipLimit=2.0）
// 对高动态范围照片可提高到 3.0~4.0
val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))

// 2. 双边滤波直径调小（当前 9~15）
// 减少滤波半径，保留更多细节边缘
val diameter = lerp(5, 11, detailLevel).toInt()  // 原为 9~15

// 3. Canny 阈值降低（当前 30~90）
// 检测更多弱边缘，再配合形态学去噪
val cannyLow = lerp(20, 50, detailLevel).toInt()   // 原为 30~60
val cannyHigh = lerp(60, 120, detailLevel).toInt() // 原为 90~180

// 4. 加权融合比例调整
// 增加 Canny 权重，让线条更精细
Core.addWeighted(adaptive, 0.5, canny, 0.5, 0.0, combined) // 原为 0.6/0.4

// 5. 尝试 MORPH_GRADIENT 替代 MORPH_CLOSE 修复断线
val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
Imgproc.morphologyEx(combined, closed, Imgproc.MORPH_GRADIENT, kernel)
```

### 优缺点

| 维度 | 评价 |
|------|------|
| **实现成本** | 极低，仅需调整参数 |
| **APK 体积** | 无增加 |
| **效果上限** | 中等，受限于传统算法天花板 |
| **适用场景** | 简单照片、快速迭代验证 |

### 预期工时

0.5 ~ 1 天（参数调优 + 真机测试）

---

## 选项 2：XDoG 引擎（纯 OpenCV，零额外依赖）

### 目标
引入学术界公认的艺术线描最佳传统算法 —— **XDoG（Extended Difference of Gaussians）**，替换当前的自适应阈值 + Canny 组合，获得更具艺术感的线稿效果。

### 技术原理

XDoG 通过两个不同尺度的高斯核差分来检测边缘，并引入非线性阈值控制线条风格：

```
DoG = G(σ) * I - G(k·σ) * I
XDoG = 1 + tanh(φ · (DoG - ε))   // 非线性阈值，产生清晰黑白线条
```

相比 Canny：
- 线条更连贯、有粗细变化（艺术感更强）
- 对纹理噪声有一定抑制作用
- 参数可调性强（线条密度、粗细、对比度）

### 实现方案

```kotlin
class XDoGLineArtEngine : LineArtEngine {
    override suspend fun convert(inputBitmap: Bitmap, detailLevel: Float, onProgress: ((Float) -> Unit)?): Bitmap {
        // Step 1: 预处理（降采样 + 灰度 + 轻微高斯平滑）
        // Step 2: XDoG 核心计算
        //   - 高斯模糊 σ 和 k·σ（k=1.6 经典值）
        //   - 差分得到 DoG
        //   - tanh 非线性阈值（φ 控制锐度，ε 控制阈值）
        // Step 3: 形态学后处理（闭运算修复断线）
        // Step 4: 反色输出白底黑线
    }
}
```

### 关键参数映射（detailLevel 0~1）

| 参数 | detailLevel=0（极简） | detailLevel=1（精细） |
|------|----------------------|----------------------|
| σ（高斯核尺度） | 2.0 | 1.0 |
| k（尺度比） | 2.0 | 1.6 |
| φ（锐度） | 5.0 | 15.0 |
| ε（阈值） | 0.5 | 0.03 |
| 形态学核大小 | 5 | 3 |

### 进一步优化：FDoG（Flow-based XDoG）

如果 XDoG 效果仍不够好，可进一步升级到 **FDoG**：
- 先计算 **ETF（Edge Tangent Flow）** 方向场
- 沿法线方向做高斯差分（而非全图统一卷积）
- 线条沿物体边界走向，极度连贯流畅
- **代价**：实现复杂度显著提高（需 ETF 迭代计算）

### 优缺点

| 维度 | 评价 |
|------|------|
| **实现成本** | 中等，需完整实现 XDoG 数学公式 |
| **APK 体积** | 无增加（纯 OpenCV） |
| **效果上限** | 高，艺术线描感明显优于 Canny |
| **适用场景** | 追求艺术感线稿、愿意投入开发时间 |

### 预期工时

2 ~ 3 天（XDoG 核心实现 + 参数调优 + 真机测试）

---

## 选项 3：PiDiNet-Tiny ONNX 模型（深度学习）

### 目标
引入轻量级深度学习模型 **PiDiNet-Tiny**，利用神经网络的语义理解能力区分"有意义的轮廓"和"无关纹理"，获得当前技术水平下最高质量的线稿。

### 技术原理

PiDiNet（Pixel Difference Networks, ICCV 2021）是专为高效边缘检测设计的轻量 CNN：

- **核心创新**：用像素差分（Pixel Difference）替代传统卷积，大幅降低参数量
- **Tiny 变体**：仅 **~73K 参数**，ONNX 模型约 **0.3MB**
- **特点**：速度快、无需预训练（从头训练）、边缘定位精度高

相比传统算法：
- 能区分人物轮廓和衣服花纹（语义理解）
- 输出线条干净、连续
- 对不同场景泛化性好

### 实现方案

```kotlin
class PiDiNetLineArtEngine(context: Context) : LineArtEngine {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        // 加载 assets/pidinet_tiny.onnx
        val modelBytes = context.assets.open("pidinet_tiny.onnx").readBytes()
        ortSession = ortEnvironment.createSession(modelBytes)
    }

    override suspend fun convert(inputBitmap: Bitmap, detailLevel: Float, onProgress: ((Float) -> Unit)?): Bitmap {
        // Step 1: 预处理
        //   - resize 到 512x512（模型输入固定尺寸）
        //   - normalize: [0, 255] → [0, 1]
        //   - NHWC → NCHW: FloatArray(1, 3, 512, 512)
        onProgress?.invoke(0.2f)

        // Step 2: ONNX 推理
        //   - 输入: "input" → FloatTensor
        //   - 输出: "output" → FloatArray(1, 1, 512, 512)
        val output = ortSession.run(mapOf("input" to inputTensor))
        onProgress?.invoke(0.6f)

        // Step 3: 后处理
        //   - sigmoid 激活（如模型未包含）
        //   - threshold 二值化
        //   - resize 回原图尺寸
        //   - 形态学清理（可选）
        onProgress?.invoke(0.9f)

        // Step 4: 输出 Bitmap（白底黑线）
    }
}
```

### 依赖变更

```kotlin
// app/build.gradle.kts
dependencies {
    // ONNX Runtime Android（约 3-5MB，按 ABI 拆分后用户实际下载 1-2MB）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
```

```
app/src/main/assets/
└── pidinet_tiny.onnx    # ~0.3MB
```

### 模型获取路径

1. **官方实现**：https://github.com/hellozhuo/pidinet
2. **导出 ONNX**：
   ```python
   import torch
   from models.pidinet import pidinet_tiny
   model = pidinet_tiny()
   # 加载预训练权重...
   dummy_input = torch.randn(1, 3, 512, 512)
   torch.onnx.export(model, dummy_input, "pidinet_tiny.onnx",
                     input_names=["input"], output_names=["output"],
                     opset_version=13, dynamic_axes={"input": {2: "H", 3: "W"}})
   ```
3. **替代方案**：LDC（Lightweight Dense CNN）
   - 参数量 674K，ONNX 模型约 1-2MB
   - 专为移动端设计，精度更高
   - 官方：https://github.com/xavysp/LDC

### 优缺点

| 维度 | 评价 |
|------|------|
| **实现成本** | 中高，需 ONNX Runtime 集成 + 模型转换 |
| **APK 体积** | +0.3MB 模型 + 3-5MB ONNX Runtime |
| **效果上限** | 极高，当前技术水平最佳 |
| **推理速度** | 50-150ms（中端手机 CPU），可接受 |
| **适用场景** | 追求最高线稿质量、可接受少量体积增加 |

### 预期工时

3 ~ 5 天（ONNX Runtime 集成 + 模型转换/获取 + 预处理对齐 + 真机测试）

---

## 三方案对比

| 维度 | 选项 1（参数优化） | 选项 2（XDoG 引擎） | 选项 3（PiDiNet-Tiny） |
|------|-------------------|-------------------|----------------------|
| **实现难度** | ⭐ 低 | ⭐⭐⭐ 中 | ⭐⭐⭐⭐ 中高 |
| **APK 体积** | 无增加 | 无增加 | +3.5MB（可接受） |
| **线稿质量** | ⭐⭐⭐ 中等 | ⭐⭐⭐⭐ 高（艺术感） | ⭐⭐⭐⭐⭐ 极高 |
| **纹理噪声** | 部分抑制 | 较好抑制 | 几乎完全消除 |
| **语义理解** | ❌ 无 | ❌ 无 | ✅ 有 |
| **开发周期** | 0.5~1 天 | 2~3 天 | 3~5 天 |
| **维护成本** | 低 | 低 | 中（模型版本管理） |

---

## 推荐实施路径

### 短期（当前已完成的优化）

已完成选项 1 的 7 项参数优化。建议在真机上用多张照片（人物、风景、宠物、静物）测试效果，记录问题场景。

### 中期（如果选项 1 仍不满足）

**优先实现 XDoG 引擎**（选项 2）：
- 零额外依赖，纯 OpenCV 实现
- 艺术线描风格非常适合儿童涂色应用
- 实现后可与当前管线并行，让用户切换对比

### 长期（追求极致效果）

**接入 PiDiNet-Tiny ONNX**（选项 3）：
- 模型仅 0.3MB，ONNX Runtime 体积可控
- 语义理解能力带来质的飞跃
- 可作为"高清模式"，XDoG 作为"快速模式"

---

## 附录：测试建议

无论选择哪个方案，请用以下**标准测试集**验证效果：

| 场景 | 测试重点 |
|------|---------|
| 人像（面部特写） | 轮廓是否清晰、眼睛/嘴是否保留、皮肤毛孔是否被误检 |
| 人像（全身） | 衣物纹理是否被过滤、肢体轮廓是否连贯 |
| 宠物（猫/狗） | 毛发细节处理、是否产生过多碎线 |
| 植物（花/树） | 叶片脉络是否保留、背景是否干净 |
| 静物（水果/玩具） | 简单物体的边缘准确度 |
| 低光照照片 | 暗部轮廓是否丢失 |
| 高对比度照片 | 亮部是否过曝、细节是否保留 |

建议将同一组照片在 3 个方案下的输出并排放置对比，直观评估差异。
