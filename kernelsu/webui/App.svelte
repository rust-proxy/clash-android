<script>
  import { onMount } from "svelte";
  import { readSnapshot } from "./snapshot.js";
  import { addSample } from "./traffic.js";
  import Diagnostics from "./Diagnostics.svelte";
  import Overview from "./Overview.svelte";

  let view = $state("overview");
  let automatic = $state(true);
  let busy = $state(false);
  let snapshot = $state(null);
  let status = $state("");
  let history = $state({});
  let generation = 0;
  let timer;
  let mounted = false;
  let pending = false;

  async function refresh() {
    clearTimeout(timer);
    if (!mounted || document.hidden) return;
    if (busy) {
      pending = true;
      return;
    }
    const current = generation;
    busy = true;
    try {
      const result = await readSnapshot(view);
      if (current !== generation) return;
      if (view === "overview") history = addSample(history, result, performance.now());
      snapshot = result;
      status = "采样于 " + new Date().toLocaleTimeString();
    } catch (error) {
      if (current !== generation) return;
      status = error.message;
      snapshot = null;
      history = {};
    } finally {
      busy = false;
      if (pending) {
        pending = false;
        refresh();
      } else if (current === generation && view === "overview" && automatic && window.ksu?.exec) {
        timer = setTimeout(refresh, snapshot?.length ? 2000 : 10000);
      }
    }
  }

  function invalidate() {
    generation++;
    clearTimeout(timer);
    pending = false;
    history = {};
  }

  function changeView(next) {
    if (view === next) return;
    invalidate();
    view = next;
    snapshot = null;
    status = "";
    refresh();
  }

  function toggleAutomatic() {
    automatic = !automatic;
    if (automatic) {
      invalidate();
      refresh();
    } else {
      clearTimeout(timer);
    }
  }

  onMount(() => {
    mounted = true;
    function visibilityChanged() {
      invalidate();
      if (!document.hidden && (snapshot === null || (view === "overview" && automatic))) refresh();
    }
    document.addEventListener("visibilitychange", visibilityChanged);
    refresh();
    return () => {
      mounted = false;
      invalidate();
      document.removeEventListener("visibilitychange", visibilityChanged);
    };
  });
</script>

<main>
  <header>
    <div><h1>TPROXY 仪表盘</h1><p id="updated">{status || "正在读取设备数据…"}</p></div>
    <button type="button" disabled={busy} onclick={refresh}>刷新</button>
  </header>
  <nav aria-label="仪表盘视图">
    <button aria-pressed={view === "overview"} onclick={() => changeView("overview")}>运行总览</button>
    <button aria-pressed={view === "diagnostics"} onclick={() => changeView("diagnostics")}>网络诊断</button>
  </nav>
  {#if view === "overview"}
    <div class="toolbar">
      <h2>内核状态</h2>
      <label><input type="checkbox" checked={automatic} onchange={toggleAutomatic}>自动刷新</label>
    </div>
    {#if snapshot === null}
      <section>{busy ? "正在读取内核状态…" : "未能获取内核状态"}</section>
    {:else if !snapshot.length}
      <section class="empty"><h3>内核未运行</h3><p class="hint">在 Clash Android 启动 KernelSU TPROXY 后可查看流量和连接。</p></section>
    {:else}
      {#each snapshot as core (core.pid)}
        <Overview {core} samples={history[core.pid]?.points ?? []} />
      {/each}
    {/if}
  {:else}
    <Diagnostics {snapshot} placeholder={busy ? "读取中…" : "读取失败"} />
  {/if}
</main>
