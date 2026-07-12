# Robot Face

这是一个基于Android的机器人表情模块，使用现代Android开发技术栈构建。

## 演示

https://github.com/user-attachments/assets/164f2b17-9f0d-4329-b587-97174255f30e


## 技术栈

- Kotlin
- Jetpack Compose
- Material3
- Kotlin Serialization
- KSP (Kotlin Symbol Processing)

## 环境要求

- Android Studio Hedgehog | 2023.1.1 或更高版本
- JDK 11
- Android SDK 35
- 最低支持Android SDK版本：29 (Android 10)

## 主要依赖

- AndroidX Core KTX
- AndroidX AppCompat
- Jetpack Compose
  - UI
  - Graphics
  - Material3
  - Animation
- Kotlin Serialization
- Material Design Icons Extended
- Google Fonts for Compose

## 表情设计

### 眼睛设计

机器人表情的眼睛由以下参数控制：

1. 基础参数
   - `leftEyeCornerRadius`/`rightEyeCornerRadius`: 眼睛边缘角度
   - `leftEyeHorizontalRadius`/`rightEyeHorizontalRadius`: 眼睛水平半径
   - `leftTopEyeRadius`/`rightTopEyeRadius`: 上眼皮垂直半径
   - `leftBottomEyeRadius`/`rightBottomEyeRadius`: 下眼皮垂直半径

2. 变换参数
   - 旋转：`eyesRotate`（整体旋转）
   - 位移：`eyesHorizontalTransition`/`eyesVerticalTransition`（整体位移）
   - 缩放：`eyesScaleX`/`eyesScaleY`（整体缩放）
   - 单眼控制：每个眼睛都有独立的旋转、位移和缩放参数

3. 颜色控制
   - `eyesFillColor`: 眼睛填充颜色

### 动作设计

动作系统由以下组件构成：

1. 基础动作
   - 位置控制：`actionHorizontalTransition`/`actionVerticalTransition`
   - 旋转控制：`actionRotate`
   - 缩放控制：`actionScale`
   - 动作图标：`actionSample`

2. 动画效果
   - 支持无限循环动画
   - 支持关键帧动画
   - 支持多种缓动效果（EaseIn、EaseOut等）

### 预设表情

系统包含多种预设表情：

1. 基础表情
   - `Ordinary`: 普通表情
   - `Blink`: 眨眼
   - `Sadness`: 伤心
   - `Happiness`: 开心
   - `Angry`: 生气

2. 特殊表情
   - `Music`: 音乐表情
   - `Rainy`: 下雨表情
   - `Sunny`: 晴天表情
   - `Cloudy`: 多云表情
   - `SparkLight`: 闪光表情
   - `Coffee`: 咖啡表情
   - `SunGlasses`: 墨镜表情
   - `TakePhoto`: 拍照表情
   - `Focus`: 专注表情

3. 自定义表情

支持json格式的表情参数下发，example:
```json
{
	"leftEyeCornerRadius": {
		"targetValue": 50.0
	},
	"rightEyeCornerRadius": {
		"targetValue": 50.0
	},
	"leftEyeHorizontalRadius": {
		"targetValue": 50.0
	},
	"rightEyeHorizontalRadius": {
		"targetValue": 50.0
	},
	"leftTopEyeRadius": {
		"targetValue": 10.0
	},
	"leftBottomEyeRadius": {
		"targetValue": 50.0
	},
	"rightTopEyeRadius": {
		"targetValue": 10.0
	},
	"rightBottomEyeRadius": {
		"targetValue": 50.0
	},
	"eyesFillColor": "#FF0000",
	"eyesVerticalTransition": {
		"targetValue": 10.0,
		"duration": 600,
		"infinite": true,
		"repeatMode": "Reverse"
	},
	"leftEyeRotate": {
		"targetValue": 15.0,
		"duration": 300
	},
	"rightEyeRotate": {
		"targetValue": -15.0,
		"duration": 300
	},
	"actionSample": {
		"sample": "🫵"
	},
	"actionHorizontalTransition": {
		"targetValue": -80.0
	},
	"actionVerticalTransition": {
		"initialValue": 90.0,
		"targetValue": 100.0,
		"duration": 600,
		"infinite": true,
		"repeatMode": "Reverse"
	}
}
```


## 构建说明

1. 确保已安装所有必要的开发工具
2. 克隆项目到本地
3. 在Android Studio中打开项目
4. 等待Gradle同步完成
5. 运行项目或构建模块

## 注意事项

- 项目使用KSP进行代码生成(其实不是必要的)
- 使用Kotlin 11作为JVM目标版本
- 支持Android 10及以上版本
