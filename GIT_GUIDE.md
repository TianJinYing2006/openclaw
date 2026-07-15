# Git 小组协作练习

## 1. 第一次提交项目

```bash
git init -b master
git status
git add .
git commit -m "初始化 Java 项目"
git remote add origin git@codeserver.youkeda.com:wangentong/ceshi.git
git push -u origin master
```

## 2. 每个人创建自己的分支

把 `你的名字` 换成自己的姓名拼音或 GitLab 用户名：

```bash
git switch -c 你的名字
git push -u origin 你的名字
```

例如：

```bash
git switch -c wangentong
git push -u origin wangentong
```

以后先进入自己的分支，再修改代码：

```bash
git switch wangentong
git pull
git status
git add .
git commit -m "说明这次修改了什么"
git push
```

## 3. 查看状态、差异和日志

```bash
git status
git diff
git diff --staged
git log --oneline --graph --decorate --all
git show 提交编号
```

- `git diff`：查看还没有执行 `git add` 的修改。
- `git diff --staged`：查看已经执行 `git add`、准备提交的修改。
- `git log --oneline --graph --decorate --all`：用图形形式查看所有分支的提交历史。

## 4. 合并分支

把个人分支合并到共享的 `master` 分支：

```bash
git switch master
git pull
git merge wangentong
git push
```

团队项目中通常先把个人分支推送到 GitLab，再创建 Merge Request，由组长检查后合并。

## 5. 解决冲突

合并时出现 `CONFLICT` 后：

1. 打开冲突文件，找到 `<<<<<<<`、`=======`、`>>>>>>>`。
2. 决定保留哪一部分，删除这些冲突标记。
3. 保存文件并确认程序可以运行。
4. 完成合并提交：

```bash
git add 冲突文件
git commit -m "解决合并冲突"
git push
```

如果想放弃本次合并：

```bash
git merge --abort
```

## 6. `.gitignore` 的作用

应该上传：源代码、`pom.xml`、配置示例、说明文档。

通常不上传：

- `.idea/`、`*.iml`：每个人电脑上的 IntelliJ 设置。
- `target/`、`out/`、`*.class`：可以重新生成的编译结果。
- `node_modules/`：可以通过 npm 重新安装的依赖。
- `.env`、本地密码和密钥：敏感信息。
- 日志、系统缓存和临时文件。

查看某个文件为什么被忽略：

```bash
git check-ignore -v 文件路径
```

## 7. IntelliJ IDEA 中查看差异

- 左侧项目树中，修改过的文件会变成蓝色，新文件通常为绿色。
- 右键文件，选择“Git → 显示差异”查看修改前后对比。
- 打开底部“版本控制”窗口，在“本地更改”中查看所有未提交文件。
- 打开底部“Git”窗口的“日志”页，查看提交记录和分支图。
- 右下角分支名称可以创建、切换、合并和删除分支。
- 出现冲突时，IntelliJ 会显示三栏合并工具：左边和右边是两个版本，中间是最终结果。
