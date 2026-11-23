<script>
  import { bytes, windowMs } from "./traffic.js";
  let { core, samples } = $props();
  let stats = $derived(core.stats);
  let latest = $derived(samples[samples.length - 1]);
  let peak = $derived(Math.max(1, ...samples.map(p => Math.max(p.up, p.down))));
  function line(key) {
    return samples.map(p => {
      const x = 600 * (1 - (latest.time - p.time) / windowMs);
      return x + "," + (96 - 92 * p[key] / peak);
    }).join(" ");
  }
  function target({ host, destinationIP, destinationPort }) {
    const name = host || destinationIP;
    if (!name) return "未知目标";
    return (name.includes(":") ? "[" + name + "]" : name) + ":" + destinationPort;
  }
</script>

<section class="overview">
  <div class="section-title"><h2>进程运行中</h2><span class="hint">PID {core.pid} · UID {core.uid}</span></div>
  {#if stats}
    <dl class="metrics">
      <div><dt>累计下载</dt><dd>{bytes(stats.downloadTotal)}</dd></div>
      <div><dt>累计上传</dt><dd>{bytes(stats.uploadTotal)}</dd></div>
      <div><dt>活跃连接</dt><dd>{stats.connectionCount}</dd></div>
      <div><dt>内存占用</dt><dd>{bytes(stats.memory)}</dd></div>
    </dl>
  {:else}
    <p class="hint">无法读取连接统计：{core.error}</p>
  {/if}
</section>
{#if stats}
  <section>
    <div class="section-title"><h2>流量趋势</h2><span class="hint">最近 60 秒</span></div>
    <div class="legend">
      <span class="down">↓ 下载　{latest ? bytes(latest.down) + "/s" : "—"}</span>
      <span class="up">↑ 上传　{latest ? bytes(latest.up) + "/s" : "—"}</span>
    </div>
    {#if samples.length >= 2}
      <span class="axis">{bytes(peak)}/s</span>
      <svg class="chart" viewBox="0 0 600 100" preserveAspectRatio="none" role="img" aria-label="最近 60 秒的上传和下载速率曲线">
        <path d="M0 4H600 M0 50H600 M0 96H600" class="grid" />
        <polyline class="download" points={line("down")}/>
        <polyline class="upload" points={line("up")}/>
      </svg>
      <div class="axis"><span>60 秒前</span><span>现在</span></div>
    {:else}
      <p class="chart-empty">等待更多流量样本</p>
    {/if}
  </section>
  <section>
    <div class="section-title"><h2>活跃连接</h2><span class="hint">按收发总量 · 前 5 条</span></div>
    {#each stats.connections as connection}
      <div class="connection">
        <strong>{target(connection.metadata)}</strong><span>{bytes(connection.download + connection.upload)}</span>
        <small>{connection.metadata.network.toUpperCase()} · {connection.rule || "未提供规则"}{connection.chains.length ? " · " + connection.chains.join(" → ") : ""}</small>
      </div>
    {:else}
      <p class="hint">采样时没有活跃连接</p>
    {/each}
  </section>
{/if}
