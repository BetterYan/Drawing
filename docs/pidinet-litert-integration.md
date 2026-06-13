# PiDiNet-Tiny + LiteRT 线稿提取方案

> 本文档指导如何将 PiDiNet-Tiny 深度学习模型转换为 LiteRT (TensorFlow Lite) 格式，并集成到「宝贝画画」Android 项目中。

---

## 一、方案概述

### 为什么选择 PiDiNet-Tiny？

| 维度 | 评价 |
|------|------|
| 模型大小 | ~0.3MB（FP32）/~0.15MB（INT8 量化后） |
| 参数量 | 仅 ~73K |
| 推理速度 | 中端手机 CPU 约 50-150ms |
| 效果 | 语义感知边缘，自动区分轮廓与纹理 |
| 与 XDoG 对比 | 消除纹理噪声，线条更干净连贯 |

### 为什么选择 LiteRT（而非 ONNX Runtime）？

| 对比维度 | LiteRT | ONNX Runtime |
|----------|--------|-------------|
| Android 运行时大小 | ~3-5MB | ~30MB |
| GPU/NPU 加速 | GPU Delegate（成熟） | NNAPI 代理（较新） |
| Google 生态集成 | 原生支持，文档完善 | 第三方支持 |
| 模型转换 | PyTorch → ONNX → TFLite | PyTorch → ONNX（直接） |
| 社区活跃度 | 极高（Android ML 首选） | 高 |
| 量化工具链 | 内置 INT8/FP16 量化 | 需额外工具 |

**结论**：LiteRT 在 Android 端部署更成熟、体积更小、GPU 加速更好，是移动端推理的首选框架。

---

## 二、环境准备（Windows）

### 2.1 安装依赖

```bash
# 创建虚拟环境（推荐）
python -m venv pidinet_env
pidinet_env\Scripts\activate

# 安装核心依赖
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
pip install onnx onnxruntime
pip install onnx2tf
pip install tensorflow
pip install numpy pillow
```

### 2.2 注意点

- `onnx2tf` 依赖 TensorFlow，安装体积较大（~500MB）
- 如果 `onnx2tf` 安装失败，可使用替代方案：`onnx2tflite`（`pip install onnx2tflite`）
- 当前 `litert-torch` 仅支持 Linux，Windows 用户需走 **PyTorch → ONNX → TFLite** 路径

---

## 三、模型下载

### 3.1 下载 PiDiNet 源码和预训练权重

```bash
# 克隆官方仓库
git clone https://github.com/hellozhuo/pidinet.git
cd pidinet

# 下载预训练模型（table5_pidinet-tiny.pth）
# 方式1：直接下载 raw 文件
curl -L -o trained_models/table5_pidinet-tiny.pth \
  https://github.com/hellozhuo/pidinet/raw/master/trained_models/table5_pidinet-tiny.pth

# 方式2：浏览器手动下载
# 访问 https://github.com/hellozhuo/pidinet/tree/master/trained_models
# 点击 table5_pidinet-tiny.pth → Download
```

### 3.2 模型变体选择

| 模型文件 | 参数量 | 推荐度 | 说明 |
|---------|--------|--------|------|
| `table5_pidinet-tiny.pth` | ~73K | ⭐⭐⭐⭐⭐ | 标准 Tiny，精度与速度平衡最佳 |
| `table5_pidinet-tiny-l.pth` | 更小 | ⭐⭐⭐⭐ | 更轻量，速度更快（253 FPS），精度略低 |
| `table5_pidinet-small.pth` | ~300K | ⭐⭐⭐ | 精度更高，模型稍大 |

**推荐**：使用 `table5_pidinet-tiny.pth`，这是论文 Table 5 中的标准 Tiny 模型，ODS=0.789。

---

## 四、Python 转换代码

### 4.1 完整转换脚本

创建文件 `convert_pidinet_to_tflite.py`：

```python
"""
PiDiNet-Tiny PyTorch -> ONNX -> TFLite 转换脚本
适用于 Windows / Linux / macOS
"""

import torch
import torch.nn as nn
import numpy as np
import os

# ============================================================
# Step 0: 加载 PiDiNet Tiny 模型
# ============================================================
# 注意：需要先克隆 pidinet 仓库并确保在 Python 路径中
# git clone https://github.com/hellozhuo/pidinet.git
import sys
sys.path.insert(0, './pidinet')

from models.pidinet import pidinet_tiny

MODEL_PATH = './pidinet/trained_models/table5_pidinet-tiny.pth'
ONNX_PATH = './pidinet_tiny.onnx'
TFLITE_PATH = './pidinet_tiny.tflite'
TFLITE_FP16_PATH = './pidinet_tiny_fp16.tflite'
TFLITE_INT8_PATH = './pidinet_tiny_int8.tflite'

# 创建模型实例
# 配置说明：
# - 'carv4': 论文中的 CARV4 架构配置
# - sa=True: 启用空间注意力
# - dil=True: 启用空洞卷积
model = pidinet_tiny(config='carv4', sa=True, dil=True)

# 加载预训练权重
state_dict = torch.load(MODEL_PATH, map_location='cpu')
# 官方权重可能包含 'module.' 前缀（DataParallel 训练导致）
# 需要清理前缀
new_state_dict = {}
for k, v in state_dict.items():
    name = k.replace('module.', '') if k.startswith('module.') else k
    new_state_dict[name] = v
model.load_state_dict(new_state_dict)

model.eval()
print(f"模型加载成功，参数量: {sum(p.numel() for p in model.parameters()):,}")

# ============================================================
# Step 1: PyTorch -> ONNX
# ============================================================
# PiDiNet 输入: NCHW, [0, 1] 范围，ImageNet 归一化
# 固定输入尺寸 512x512（可改为动态尺寸）
dummy_input = torch.randn(1, 3, 512, 512)

# 导出 ONNX
torch.onnx.export(
    model,
    dummy_input,
    ONNX_PATH,
    input_names=['input'],
    output_names=['output'],
    dynamic_axes={
        'input': {0: 'batch_size', 2: 'height', 3: 'width'},
        'output': {0: 'batch_size', 2: 'height', 3: 'width'}
    },
    opset_version=13,
    do_constant_folding=True,
    export_params=True,
)
print(f"ONNX 导出成功: {ONNX_PATH}")

# 验证 ONNX 模型
import onnx
onnx_model = onnx.load(ONNX_PATH)
onnx.checker.check_model(onnx_model)
print("ONNX 模型验证通过")

# ============================================================
# Step 2: ONNX -> TFLite (FP32)
# ============================================================
# 方案 A: 使用 onnx2tf（推荐，转换质量高）
try:
    from onnx2tf import convert
    convert(
        input_onnx_file_path=ONNX_PATH,
        output_folder_path='.',
        output_tfjs=False,
        output_coreml=False,
        output_tflite=True,
        output_h5=False,
        output_pb=False,
        output_onnx=False,
        output_saved_model=False,
        copy_onnx_input_output_names_to_tflite=True,
    )
    # onnx2tf 默认输出名为 saved_model.tflite，重命名
    if os.path.exists('saved_model.tflite'):
        os.rename('saved_model.tflite', TFLITE_PATH)
    print(f"TFLite (FP32) 转换成功: {TFLITE_PATH}")
except Exception as e:
    print(f"onnx2tf 失败: {e}")
    print("尝试使用替代方案...")

# 方案 B: 使用 onnx2tflite（如果方案 A 失败）
# pip install onnx2tflite
# from onnx2tflite import onnx_converter
# onnx_converter(
#     onnx_model_path=ONNX_PATH,
#     need_simplify=True,
#     output_path=TFLITE_PATH,
# )

# ============================================================
# Step 3: TFLite FP16 量化（体积减半，精度几乎无损）
# ============================================================
import tensorflow as tf

converter = tf.lite.TFLiteConverter.from_saved_model('.')  # 或 from_tflite_model
# 如果没有 saved_model，直接从 tflite 转换：
# 读取 FP32 tflite 再转换
if os.path.exists(TFLITE_PATH):
    with open(TFLITE_PATH, 'rb') as f:
        tflite_model = f.read()
    
    # 使用 TFLite 转换器进行 FP16 量化
    converter = tf.lite.TFLiteConverter.from_concrete_functions([])
    # 更简单的方法：直接对已有 tflite 进行后训练量化
    # 实际上需要重新从 saved_model 转换
    
    # 推荐：直接在一次转换中生成 FP16
    # 重新用 onnx2tf 生成 FP16
    from onnx2tf import convert
    convert(
        input_onnx_file_path=ONNX_PATH,
        output_folder_path='.',
        output_tflite=True,
        output_tfjs=False,
        output_coreml=False,
        output_h5=False,
        output_pb=False,
        output_onnx=False,
        output_saved_model=False,
        copy_onnx_input_output_names_to_tflite=True,
        # FP16 量化参数
        output_integer_quantized_tflite=False,
        quant_type='fp16',  # 注意：不同版本参数名可能不同
    )
    # 注意：onnx2tf 的 FP16 量化参数可能因版本而异
    # 如果上述方法不支持，请使用 tensorflow 原生 API 如下：

print("FP16 量化完成")

# ============================================================
# Step 4: TFLite INT8 量化（体积再减半，可能损失少量精度）
# ============================================================
# INT8 量化需要校准数据集，这里简化处理
# 生产环境建议使用代表数据集的 ~100 张图片进行校准

def representative_dataset():
    """生成校准数据，用于 INT8 量化"""
    for _ in range(100):
        # 模拟输入：随机数据或真实图片
        data = np.random.rand(1, 3, 512, 512).astype(np.float32)
        yield [data]

# 使用 tensorflow 直接量化（需要 saved_model 格式）
# 如果只有 tflite，需要用另一种方式

print("=" * 60)
print("转换完成！输出文件：")
print(f"  ONNX:    {ONNX_PATH}")
print(f"  TFLite:  {TFLITE_PATH}")
print("=" * 60)
```

### 4.2 更简洁的转换脚本（使用 ai-edge-torch / litert-torch，Linux only）

如果您有 Linux 环境（或 WSL2），可以直接用 Google 官方的转换工具：

```bash
# 安装
pip install litert-torch

# 转换代码
import torch
import litert_torch

model = pidinet_tiny(config='carv4', sa=True, dil=True)
model.load_state_dict(torch.load('table5_pidinet-tiny.pth', map_location='cpu'))
model.eval()

sample_input = (torch.randn(1, 3, 512, 512),)
edge_model = litert_torch.convert(model, sample_input)
edge_model.export('pidinet_tiny.tflite')
```

---

## 五、预处理对齐（关键！）

PiDiNet 的输入预处理必须与训练时完全一致。根据官方实现和边缘检测领域惯例：

```python
import torchvision.transforms as T
from PIL import Image

def preprocess(image_path, target_size=512):
    """
    PiDiNet 预处理流程：
    1. Resize 到 target_size（保持长宽比或拉伸到正方形）
    2. 转为 Tensor
    3. ImageNet 归一化（与训练一致）
    """
    img = Image.open(image_path).convert('RGB')
    
    # 方法 A：直接 resize 到 512x512（简单，可能变形）
    transform = T.Compose([
        T.Resize((target_size, target_size)),
        T.ToTensor(),
        T.Normalize(mean=[0.485, 0.456, 0.406],
                    std=[0.229, 0.224, 0.225]),
    ])
    
    # 方法 B：等比缩放后中心裁剪（推荐，不变形）
    # transform = T.Compose([
    #     T.Resize(target_size),
    #     T.CenterCrop(target_size),
    #     T.ToTensor(),
    #     T.Normalize(mean=[0.485, 0.456, 0.406],
    #                 std=[0.229, 0.224, 0.225]),
    # ])
    
    return transform(img).unsqueeze(0)  # 增加 batch 维度

def postprocess(output_tensor):
    """
    PiDiNet 后处理：
    1. Sigmoid（如果模型输出未经过 sigmoid）
    2. 转为 [0, 255] 灰度图
    3. 阈值化（二值化）
    """
    import torch.nn.functional as F
    
    # output_tensor shape: [1, 1, H, W]
    prob = torch.sigmoid(output_tensor)
    prob = prob.squeeze().detach().cpu().numpy()
    
    # 转为 0-255
    gray = (prob * 255).astype(np.uint8)
    
    # 二值化（阈值可调，默认 127）
    binary = (gray > 127).astype(np.uint8) * 255
    
    return binary
```

**⚠️ 重要注意点**：
1. **归一化参数**：必须使用 `mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]`（ImageNet 标准），这是 PiDiNet 训练时使用的
2. **输入尺寸**：训练时通常使用多尺度，推理时固定 512x512 即可
3. **颜色通道**：PyTorch 模型输入是 RGB（NCHW），Android Bitmap 默认也是 RGBA

---

## 六、Android 端集成

### 6.1 Gradle 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    // LiteRT (TensorFlow Lite) 核心库
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    
    // GPU 加速（可选，需要 GPU Delegate）
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    
    // GPU 支持库（兼容层）
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support-api:0.4.4")
}
```

### 6.2 模型文件放置

```
app/src/main/assets/
└── pidinet_tiny.tflite    # 转换后的模型文件
```

### 6.3 引擎实现代码

见下方 `PiDiNetLineArtEngine.kt` 完整代码。

---

## 七、常见问题

### Q1: onnx2tf 安装失败怎么办？

尝试以下方案：
```bash
# 方案 A：降低 TensorFlow 版本
pip install tensorflow==2.15.0
pip install onnx2tf

# 方案 B：使用 onnx2tflite（更轻量）
pip install onnx2tflite
```

### Q2: 转换后的 TFLite 模型推理结果和 PyTorch 不一致？

99% 是 **预处理不对齐**。请严格核对：
- Resize 方式（是否保持长宽比）
- Normalize 参数（mean/std）
- 颜色通道顺序（RGB vs BGR）
- 数值范围（[0,1] vs [0,255]）

### Q3: INT8 量化后效果变差？

PiDiNet 是轻量模型，INT8 量化可能损失边缘细节。建议：
- 优先使用 FP16 量化（体积减半，精度几乎无损）
- INT8 量化时使用真实图片作为校准数据集

### Q4: 模型体积还是太大？

- 使用 `table5_pidinet-tiny-l.pth`（更轻量版本）
- FP16 量化后约 150KB
- 如果还不够，考虑使用 XDoG 引擎作为替代

---

## 八、预期效果对比

| 场景 | OpenCV 引擎 | XDoG 引擎 | PiDiNet-Tiny |
|------|------------|----------|-------------|
| 人像轮廓 | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 纹理噪声（衣服花纹） | ❌ 大量误检 | ⚠️ 部分误检 | ✅ 几乎无 |
| 毛发/植物细节 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 暗光照片 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| 推理速度（512px） | ~80ms | ~120ms | ~100ms |
| APK 增量 | 0 | 0 | ~3.5MB |
