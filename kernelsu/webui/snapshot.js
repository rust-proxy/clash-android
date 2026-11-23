let sequence = 0;

export function readSnapshot(view) {
  return new Promise((resolve, reject) => {
    const callback = "clashSnapshot" + (++sequence);
    const timer = setTimeout(() => {
      delete window[callback];
      reject(new Error("读取超时"));
    }, 15000);
    window[callback] = (code, stdout, stderr) => {
      clearTimeout(timer);
      delete window[callback];
      try {
        if (code !== 0) throw new Error(stderr.trim() || "诊断采样失败");
        resolve(JSON.parse(stdout));
      } catch (error) {
        reject(error);
      }
    };
    try {
      if (!window.ksu?.exec) throw new Error("请从 KernelSU 管理器打开此页面");
      const command = view === "overview" ? "--overview" : "--diagnostics";
      window.ksu.exec("/data/adb/modules/clash_android_tproxy/diagnostics " + command, "{}", callback);
    } catch (error) {
      clearTimeout(timer);
      delete window[callback];
      reject(error);
    }
  });
}
