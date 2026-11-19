# libs/

编译需要**上游 Sable 的 jar**，但它不随本仓库分发（Sable 使用 PolyForm Shield 许可）。

把实例里的 jar 复制过来，名字固定为 `sable.jar`：

```powershell
copy "D:\mc\PCL2\.minecraft\versions\1.21.1-NeoForge_21.1.265\mods\sable-neoforge-1.21.1-2.0.6.jar" `
     "libs\sable.jar"
```

本项目只在 `[2.0.6, 2.1)` 上验证过；`build.gradle` 里它是 `compileOnly`（另加一个仅用于 dev 冒烟
`runServer` 的 `runtimeOnly`），产物 jar 内不含任何 Sable class——`tools/check_jar.py` 会断言这一点。
