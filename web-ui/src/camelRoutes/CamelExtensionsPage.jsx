import React from "react";
import { PageHeader } from "@inductiveautomation/ignition-web-ui";
import { Content } from "@inductiveautomation/ignition-gateway-lib";
import { Disclosure, FormSection, Notice, Tag } from "./CamelUi";
import CamelExtensionGroups, { libraryState, libraryTone } from "./CamelExtensionGroups";
import CamelExtensionRemoval from "./CamelExtensionRemoval";

const root = "/data/apache-camel";
const size = bytes => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
export default function CamelExtensionsPage() {
  const [inventory, setInventory] = React.useState();
  const [files, setFiles] = React.useState([]);
  const [trusted, setTrusted] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [progress, setProgress] = React.useState({});
  const [message, setMessage] = React.useState("");
  const [error, setError] = React.useState("");
  const [removing, setRemoving] = React.useState("");
  const [dragging, setDragging] = React.useState(false);
  const input = React.useRef();
  const refresh = React.useCallback(async () => {
    const response = await fetch(`${root}/camel-extensions`);
    if (!response.ok) throw new Error(`Could not load extensions (HTTP ${response.status})`);
    setInventory(await response.json());
  }, []);
  React.useEffect(() => { refresh().catch(failure => setError(failure.message)); }, [refresh]);
  const choose = selected => {
    if (busy) return;
    const next = Array.from(selected);
    if (next.some(file => !file.name.endsWith(".jar") || file.size > 64 * 1024 * 1024)) {
      setError("Select .jar files no larger than 64 MB each."); return;
    }
    if (new Set(next.map(file => file.name)).size !== next.length) { setError("Choose files with unique filenames."); return; }
    setFiles(next); setProgress({}); setTrusted(false); setError(""); setMessage("");
  };
  const post = async (path, body, type) => {
    const session = await (await fetch("/data/app/session")).json();
    const response = await fetch(`${root}/${path}`, { method: "POST", headers: {
      "X-CSRF-Token": session.csrfToken, ...(type ? { "Content-Type": type } : {}),
    }, body });
    const result = await response.json();
    if (!response.ok) { const failure = new Error(result.error || `HTTP ${response.status}`); failure.status = response.status; throw failure; }
    return result;
  };
  const upload = async () => {
    setBusy(true); setError(""); setMessage("");
    try {
      for (const file of files) {
        if (progress[file.name] === "Staged") continue;
        setProgress(previous => ({ ...previous, [file.name]: "Uploading…" }));
        try {
          await post(`camel-extension-upload?confirm=true&name=${encodeURIComponent(file.name)}`, file, "application/java-archive");
          setProgress(previous => ({ ...previous, [file.name]: "Staged" }));
        } catch (failure) {
          setProgress(previous => ({ ...previous, [file.name]: "Rejected" })); throw new Error(`${file.name}: ${failure.message}`);
        }
      }
      setMessage("Libraries staged. Restart the Gateway to activate them; restarting a route is not sufficient.");
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); refresh().catch(failure => setError(failure.message)); }
  };
  const remove = async plan => {
    setBusy(true); setError("");
    try {
      await post(`camel-extension-remove?confirm=true&id=${encodeURIComponent(plan.id)}&includeUnused=${plan.includeUnused}&token=${encodeURIComponent(plan.token)}`);
      setRemoving(""); setMessage("Removal scheduled. Existing runtimes keep their libraries until Gateway restart."); await refresh();
    } finally { setBusy(false); }
  };
  const pending = inventory?.files.some(file => file.state !== "ACTIVE");
  return <><PageHeader pageTitle="Apache Camel — Extensions" /><Content>
    <div className="camel-ui camel-page camel-extensions">
      <div className="camel-extension-heading"><div>
        <p className="camel-page-intro">Manage component libraries and their dependencies. Changes apply after a Gateway restart.</p></div>
        <div className="camel-tags">{inventory && <Tag>Camel {inventory.version} · Java 17</Tag>}<a className="camel-button" href="#camel-extension-upload">Upload libraries</a></div></div>
      {error && <Notice error>{error}</Notice>}{message && <Notice>{message}</Notice>}
      {inventory?.startupError && <Notice error>Extensions were not activated: {inventory.startupError}. Review the conflicting library and restart the Gateway. The native Ignition bridge remains available.</Notice>}
      {pending && !message && <Notice>Library changes are pending. Restart the Gateway when you are ready to apply them.</Notice>}
      <CamelExtensionGroups inventory={inventory} busy={busy} onReview={setRemoving} />
      <FormSection id="camel-extension-upload" title="Upload libraries" trailing={<Tag>Restart required</Tag>}>
        <div className={`camel-upload-zone${dragging ? " is-dragging" : ""}`} onDragOver={event => { event.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)} onDrop={event => { event.preventDefault(); setDragging(false); choose(event.dataTransfer.files); }}>
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5" /></svg>
          <strong>Drop component & dependency JARs here</strong><span className="camel-muted">Multiple files · 64 MB per JAR · Camel {inventory?.version || "4.22.2"}</span>
          <input ref={input} type="file" accept=".jar" multiple disabled={busy} className="camel-sr-only" aria-label="Choose extension JARs" onChange={event => choose(event.target.files)} />
          <button type="button" className="camel-button" disabled={busy} onClick={() => input.current.click()}>Choose JAR files</button>
        </div>
        {files.length > 0 && <ul className="camel-upload-files" aria-live="polite">{files.map((file, index) => <li key={`${file.name}-${index}`}><span>{file.name}<small className="camel-muted">{size(file.size)}</small></span><Tag>{progress[file.name] || "Ready"}</Tag></li>)}</ul>}
        <label className="camel-toggle"><input type="checkbox" checked={trusted} disabled={busy} onChange={event => setTrusted(event.target.checked)} />I trust these libraries to execute code with Gateway privileges.</label>
        <div className="camel-extension-actions"><button type="button" className="camel-button camel-button--primary" disabled={busy || !trusted || !files.length} onClick={upload}>{busy ? "Working…" : files.length ? `Stage ${files.length} ${files.length === 1 ? "library" : "libraries"}` : "Stage libraries"}</button>
          <span className="camel-hint">Upload dependencies too. Required engine/host APIs and duplicate installed classes are rejected.</span></div>
      </FormSection>
      <FormSection title="Individual libraries" trailing={<Tag>{inventory?.files.length || 0} libraries</Tag>}>
      <Disclosure title="Inspect supporting libraries or manage a single JAR" summary={inventory?.files.length || 0}>
        {!inventory && !error && <p className="camel-muted">Loading libraries…</p>}
        {inventory && !inventory.files.length && <div className="camel-row-empty">No extension libraries installed. The native Ignition bridge remains available.</div>}
        {!!inventory?.files.length && <div className="camel-table-scroll"><table className="camel-extension-table"><thead><tr><th>Library</th><th>Used by groups</th><th>Source</th><th>Size</th><th>Status</th><th><span className="camel-sr-only">Actions</span></th></tr></thead><tbody>
          {inventory.files.map(file => <tr key={file.id}><td><strong>{file.name}</strong><small className="camel-library-hash" title={`SHA-256: ${file.sha256}`}>{file.sha256.substring(0, 16)}…</small></td><td><small>{(inventory.usage?.[file.id] || []).join(", ") || "Unknown / unclassified"}</small></td><td><Tag>{file.origin === "INCLUDED" ? "Included" : "Uploaded"}</Tag></td><td>{size(file.bytes)}</td><td><Tag tone={libraryTone(file.state)}>{libraryState(file.state)}</Tag></td>
            <td>{file.state !== "REMOVAL_PENDING" && <button type="button" className="camel-button camel-button--quiet" disabled={busy} onClick={() => setRemoving(file.id)} aria-label={`Review impact for ${file.name}`}>Review impact</button>}</td></tr>)}
        </tbody></table></div>}
      </Disclosure>
      </FormSection>
    </div></Content><CamelExtensionRemoval id={removing} busy={busy} onClose={() => setRemoving("")} onRemove={remove} /></>;
}
