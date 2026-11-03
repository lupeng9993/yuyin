package com.voiceassistant.shell;

/** 跑在 Shizuku（shell 权限）进程里的命令执行接口 */
interface IShellService {
    /** 执行一条 shell 命令，返回合并后的标准输出/错误输出 */
    String exec(String command);
}
