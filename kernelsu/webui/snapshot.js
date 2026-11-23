let sequence = 0;

export function readSnapshot() {
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
      window.ksu.exec("/data/adb/modules/clash_android_tproxy/diagnostics --diagnostics", "{}", callback);
    } catch (error) {
      clearTimeout(timer);
      delete window[callback];
      reject(error);
    }
  });
}
