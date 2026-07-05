# Git开启符号链接命令
#git config --global core.symlinks true
# 在项目目录下执行
#git config core.symlinks true

# 清理项目缓存
.\gradlew clean

# 仅编译arm64-v8a架构
$env:BUILD_ABI="arm64-v8a";

#.\gradlew :app:assembleDebug

.\gradlew assembleRelease
