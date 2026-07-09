
---

# 文件系统 Overlay 模块需求规格（Android Lsposed 方案）

**版本**：2.0  
**适用范围**：`/sdcard` 文件系统（排除 `/sdcard/Android`）

---

## 1. 概述

本模块为 Android 应用提供**配置驱动**的 MediaProvider Overlay 能力。通过 Lsposed 注入到 `MediaProvider` 进程，对其关键方法进行拦截与重定向，实现**数据隔离、写时复制、删除保护**等功能。

**工作原理**：  
Android 11+ 的外部存储通过 FUSE 用户态守护进程挂载，所有文件 I/O（包括通过 `java.io.File`、NDK 等的访问）最终都会转化为对 `MediaProvider` 的 Binder 调用。因此，**仅 hook MediaProvider 即可覆盖绝大部分文件操作**，无需额外拦截 libc 或 Java File 类。

**关键原则**：

- 只对 **`enable: true`** 的应用启用 Overlay。
- Overlay **只作用于 `/sdcard` 分区，但不包含 `/sdcard/Android` 子树**。
- 对未配置规则但位于 `/sdcard` 区域内的路径，默认以 **`n` 模式（隔离）** 处理。
- 未启用 Overlay 的应用，以及启用应用在 `/sdcard` 之外或 `/sdcard/Android` 内的访问，全部**透传底层**，无任何额外开销。
- 配置中所有路径均为**相对于 `/sdcard` 的相对路径**，内部统一规范化为 `/storage/emulated/{user}/...` 形式后进行匹配。

---

## 2. 配置文件规范

配置文件基于 Lsposed 的 Remote File 能力，支持动态加载。

### 2.1 顶层结构

```json
{
  "userId": 0,
  "enabled": true,
  "applications": [ ... ]
}
```

| 字段           | 类型   | 说明                         |
| -------------- | ------ | ---------------------------- |
| `userId`       | int    | 当前用户 ID。               |
| `enabled`      | bool   | 是否对当前用户启用 Overlay。 |
| `applications` | array  | 应用规则列表。               |

### 2.2 应用规则

```json
{
  "package_name": "com.example.app",
  "enable": true,
  "dirs": [
    {
      "path": "Documents",
      "mode": "r"
    },
    {
      "path": "Download",
      "mode": "w"
    }
  ]
}
```

| 字段             | 必填 | 说明                                   |
|----------------|----|--------------------------------------|
| `package_name` | 是  | 目标应用包名，用于匹配进程。                       |
| `enable`       | 是  | `true` 才启用 Overlay；`false` 时整个应用不干预。 |
| `dirs`         | 否  | 目录规则列表，可为空（此时所有合法路径均按默认 `n` 模式处理）。   |

### 2.2.1 目录规则

| 字段     | 必填 | 说明                                                     |
|--------|----|--------------------------------------------------------|
| `path` | 是  | 受控目录相对路径，**相对于 `/sdcard`**，且不可在 `/sdcard/Android` 子树内。 |
| `mode` | 是  | 工作模式，取值 `"r"`、`"w"`、`"n"`。                             |

### 2.3 运行时配置

运行时，以 **UID** 为最小控制单位。模块启动时将应用的包名配置转换为以 UID 为键的运行时配置：

```json
{
  "uid": 1000,
  "uid_name": "android",
  "dirs": [
    { "path": "Documents", "mode": "r" },
    { "path": "Download", "mode": "w" }
  ]
}
```

- 若根据 UID 找不到对应的运行时配置，则触发**重建**：扫描所有包名配置，根据系统包名→UID 映射重新生成。
- 当多个包共享同一 UID 时，`dirs` 规则取并集；若同一路径的模式冲突，按严格程度 `r > w > n` 决定最终模式。
- 应用卸载或更新导致 UID 变化时，Upper 目录会随之清理（因位于应用私有目录），模块无需额外迁移。

---

## 3. 作用域判定流程

对于每次文件操作，按以下逻辑决定是否进入 Overlay 以及使用的 mode：

```
if 当前应用未配置 enable:true → 透传底层 (PASS)

if 当前应用 enable:true:
    if 路径 P 以 "/data/" 开头 → 透传底层 (PASS)
    if P 是 "/sdcard/Android" 或以其开头 → 透传底层 (PASS)
    // 注意：处理时将真实路径（如 /storage/emulated/0/...）归一化后判断

    // 此时 P 在 /sdcard 内且不在 /sdcard/Android 子树
    将 P 转换为相对于 /sdcard 的相对路径
    在 dirs 中寻找最长前缀匹配的规则：
        if 找到 → mode = 该规则的 mode
        else    → mode = "n"  (默认隔离)

    根据 mode 执行 Overlay 操作 (r/w/n 逻辑)
```

**补充**：`/sdcard` 根目录（`/storage/emulated/{user}`）本身的操作同样遵循上述判定，若未专门配置根路径规则，则自动落入默认 `n` 模式。

---

## 4. 模式详细行为

约定：
- **Lower**：底层真实文件系统（`/storage/emulated/{user}/...`）
- **Upper**：Overlay 存储层  
  - **媒体文件**：`/storage/emulated/{user}/Android/media/{uid_name}/sdcard_redirect/`  
  - **非媒体文件**：`/storage/emulated/{user}/Android/data/{uid_name}/files/sdcard_redirect/`  
  路径结构均与操作路径的结构对应。  
  **媒体/非媒体判定**：仅当源文件的路径位于 `DCIM`、`Pictures`、`Audio`、`Music`、`Movies` 这 5 个目录之一时，视为媒体文件；否则为非媒体文件。
- **Whiteout**：Upper 中的空文件 `.wh.<name>`，用于屏蔽 Lower 同名条目。

### 4.1 `r` 模式（保护读，写时复制）

| 操作       | 行为                                                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------------------------------------|
| **读/列表** | Upper + Lower 合并视图。Upper 优先，whiteout 屏蔽对应 Lower 条目。若无 Upper 实体且 Lower 存在，则返回 Lower；均不存在则 `ENOENT`。目录列表需合并两个目录的内容并扣除 whiteout 对应的名称。 |
| **新建**   | 在 Upper 创建。若存在 whiteout 则先移除。父目录链不足时自动在 Upper 递归创建（默认权限 0755，owner 为应用 UID）。                                                        |
| **修改**   | 若文件在 Upper → 直接修改；若仅在 Lower → 先 **copy-up** 到 Upper，再修改 Upper 副本。                                                                   |
| **删除**   | 若 Upper 有实体 → 删除实体；若 Lower 有同名 → 在 Upper 创建 whiteout 屏蔽 Lower；仅 Upper 存在 → 直接删除。`rmdir` 前检查合并视图目录为空（Upper 实体 + Lower 被屏蔽后无剩余文件/目录）。 |
| **重命名**  | 源若仅在 Lower，先 copy-up 到 Upper。源和目标**必须位于同一 `r` 区域内**（否则返回 `EXDEV`）。在 Upper 内完成 rename，源位置创建 whiteout（若源原为 Lower 文件）。                 |

### 4.2 `w` 模式（透传读写，保护删除）

此模式适用于应用具有正常写入权限的目录，如 `Download`。除**删除**操作外全部透传底层，无性能损耗。**重命名不执行 copy-up，直接在底层执行**。

| 操作                      | 行为                                                                                                                                                            |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **读、新建、修改、stat、列表等**    | 直接透传底层文件系统，不检查 Upper，不产生 whiteout，无 copy-up。                                                                                                                  |
| **删除** (unlink / rmdir) | 在 Upper 对应路径创建 whiteout（自动递归创建父目录）。**不删除 Lower 实体**。若 `rmdir`，仅当合并视图（此时即 Lower）目录为空时成功；若 Upper 中存在其他 whiteout，合并视图可能非空，按原逻辑返回 `ENOTEMPTY`。                    |
| **重命名** (rename)        | 1. 若目标路径的规则为 `r` → 返回 `EACCES`（禁止写入保护目录）。<br>2. 若目标路径规则为 `w` → **直接在底层文件系统执行 rename**，不产生 copy-up 和 whiteout。<br>3. 源和目标必须位于同一文件系统且处于 `w` 模式区域内，否则返回 `EXDEV`。 |

**注意**：`w` 模式的重命名**放弃了重命名保护**（仅保护删除），如有更高保护需求，请使用 `r` 模式。

### 4.3 `n` 模式（完全隔离）

Lower 完全不可见，所有操作均发生在 Upper 层。相当于该路径对应用来说是独立存储。

| 操作       | 行为                                                        |
|----------|-----------------------------------------------------------|
| **所有操作** | 基于 Upper 执行，Lower 不参与视图。无 whiteout 屏蔽需求。删除时直接删除 Upper 实体。 |

### 4.4 关于合并视图

合并视图时，如果Upper和Lower中存在文件冲突，则返回Upper,合并后去除掉whiteout

---

## 5. 关键机制

### 5.1 Upper 层存储隔离

- 文件 Upper 路径：`/storage/emulated/{user}/Android/media/{uid_name}/sdcard_redirect/`  
- 临时工作目录 `work_dir`：`/storage/emulated/{user}/Android/data/{uid_name}/files/redirect_work/`  
  用于 copy-up 和 whiteout 创建时的原子写入（先写临时文件，再 `rename` 到目标 Upper 位置）。`work_dir` 与 Upper 处于同一文件系统，确保 `rename` 的原子性。

### 5.2 Whiteout 规则

- 名称：`.wh.<filename>`（空文件）
- 存在于 Upper 目录，屏蔽列表记录其对应的原始名称。
- 在 `r` 模式下标准生效；`w` 模式仅删除时创建；`n` 模式不使用。

### 5.3 多用户支持

路径中的 `{user}` 变量在进程启动时根据 `userId` 展开为实际用户目录（如 `0` → `/sdcard` 实质是 `/storage/emulated/0`）。模块内部路径处理一律使用规范化的真实路径（`/storage/emulated/{user}/...`）。

### 5.4 SELinux 兼容性

- `MediaProvider` 作为系统进程，拥有访问 `/sdcard` 所有区域的 SELinux 权限，包括写入各应用的私有外部存储目录（`Android/data/`、`Android/media/`）。经过主流 ROM 验证，无需额外策略补丁。
- 模块注入后以 `MediaProvider` 的 SELinux 上下文执行文件操作，可正常通过检查。

### 5.5 未匹配路径的默认 `n` 模式

若应用启用 Overlay，但操作的 `/sdcard` 路径（非 `/sdcard/Android`）未在 `dirs` 中配置，则自动应用 `n` 模式。包括 `/sdcard` 根目录本身，也受此默认规则约束。这提供了“默认隔离”的安全特性。

### 5.6 并发控制（文件锁）

对 Upper 层的写操作（copy-up、创建 whiteout、新建文件）必须严格串行化，避免多线程/多进程并发导致文件损坏或 whiteout 重复创建。实现方案如下：

- 在 `work_dir` 下使用以“规范化路径哈希值”命名的空文件作为锁。
- 操作前尝试以 `O_CREAT | O_EXCL` 创建锁文件，成功则进入临界区，操作完成后删除锁文件；若创建失败（并发冲突），等待并重试或向上层返回错误（由调用方重试）。
- 锁粒度针对具体文件/whiteout 实体，不影响其他文件的正常操作。

---

## 6. 应用场景示例

| 场景               | 配置                             | 效果                                      |
|------------------|--------------------------------|-----------------------------------------|
| 保护文档目录不被篡改       | `path: "Documents", mode: "r"` | 读正常，写入落 Upper，删除产生 whiteout，重命名受保护。     |
| 允许自由读写下载目录，但防误删  | `path: "Download", mode: "w"`  | 正常读写文件，删除被阻止（仅创建 whiteout），重命名直接底层执行。   |
| 隔离应用的临时缓存        | 不配置该路径（默认 `n`）                 | 应用看到的目录为空，所有操作在自己 Upper 中，不影响真实存储。      |
| 禁止应用访问相册         | 对 `DCIM` 不配置 → `n` 模式          | 应用完全看不到相册内容，只能操作 Upper 中的空目录。           |
| 完全隔离整个 `/sdcard` | `enable: true, dirs: []`       | 所有 `/sdcard` 路径均为 `n` 模式，应用与真实文件系统完全隔离。 |

---

## 7. 实现约束

- **MediaProvider 拦截**：使用 Lsposed 拦截以下函数（不限于）：
  - `query`, `queryInternal`
  - `openFile`, `openTypedAssetFile`
  - `insert`, `bulkInsert`, `delete`, `update`, `applyBatch`
- **性能**：对不匹配 Overlay 范围的调用直接执行原始函数；路径匹配采用前缀树；whiteout 存在性可缓存。
- **原子性**：涉及 Upper 修改的操作使用 `rename` 保证原子性（临时文件写入 `work_dir` 后 rename 到目标位置）。
- **重命名限制**：跨 mode 区域（包括跨出 Overlay 范围）返回 `EXDEV`；`w` 到 `r` 操作返回 `EACCES`。
- **目录递归创建**：当需在 Upper 创建实体时，自动按需创建父目录链（权限 0755，owner 为应用 UID）。

---

## 8. 配置示例（完整）

```json
{
  "userId": 0,
  "enabled": true,
  "applications": [
    {
      "package_name": "com.android.chrome",
      "enable": true,
      "dirs": [
        { "path": "Download", "mode": "w" },
        { "path": "Documents", "mode": "r" }
      ]
    },
    {
      "package_name": "com.example.gallery",
      "enable": true,
      "dirs": []
    }
  ]
}
```

- Chrome：`Download` 可自由读写，删除被保护，重命名直接底层执行；`Documents` 写保护；其他如 `DCIM` 等未配置路径隔离不可见。  
- Gallery：所有 `/sdcard` 内（除 `Android`）均为 `n` 模式，完全隔离。

---
