import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type Attachment, type BatchOp, type HistoryEntry } from '../lib/api'
import { useAccounts, useAttachments, useCategoryIndex, useLedger, type WithId } from '../lib/data'
import { formatMoney } from '../lib/money'
import { shortDate, uuidv7 } from '../lib/util'
import { Modal, errorText, toast } from '../components'
import { Spinner } from '../charts'
import { Icon } from '../icons'

/** A file attached (or about to be attached) to a transaction. */
export interface FileDraft { id: string; name: string; mime: string; size: number; sha256: string; existing: boolean; removed?: boolean }

/** Live attachments of a transaction, oldest first. */
export function useTxAttachments(txId?: string): WithId<Attachment>[] {
  const all = useAttachments()
  if (!txId) return []
  return (all.data ?? []).filter((a) => !a.deleted && a.transaction_id === txId).sort((a, b) => a.created_at - b.created_at)
}

/** Batch operations that save the attachment list along with the transaction. */
export function attachmentOps(txId: string, files: FileDraft[]): BatchOp[] {
  const ops: BatchOp[] = []
  for (const f of files) {
    if (f.existing && f.removed) ops.push({ op: 'delete', entity: 'attachments', id: f.id })
    else if (!f.existing && !f.removed) ops.push({ op: 'create_if_missing', entity: 'attachments', id: f.id, data: { transaction_id: txId, name: f.name, mime: f.mime, size: f.size, sha256: f.sha256, created_at: Date.now() } })
  }
  return ops
}

/**
 * Phone photos are often 3–8 MB. Large JPEG/PNG/WebP images are scaled to at
 * most 2000 px on the long side and saved as JPEG, which keeps receipts
 * readable at a fraction of the size. Other files are left as they are.
 */
export async function shrinkImage(file: File): Promise<Blob> {
  if (!/^image\/(jpeg|png|webp)$/.test(file.type) || file.size < 1_500_000) return file
  try {
    const bmp = await createImageBitmap(file)
    const scale = Math.min(1, 2000 / Math.max(bmp.width, bmp.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bmp.width * scale); canvas.height = Math.round(bmp.height * scale)
    canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height)
    const out = await new Promise<Blob | null>((res) => canvas.toBlob(res, 'image/jpeg', 0.85))
    return out && out.size < file.size ? out : file
  } catch { return file }
}

/** An object URL for an attachment file (fetched with the session, so it also works in the demo). */
export function useBlobUrl(sha?: string): string | undefined {
  const l = useLedger()
  const q = useQuery({ queryKey: [l.id, 'blob', sha], queryFn: () => api.fetchBlob(l.id, sha!), enabled: !!sha, staleTime: Infinity, gcTime: 5 * 60_000 })
  const [url, setUrl] = useState<string>()
  useEffect(() => {
    if (!q.data) return
    const u = URL.createObjectURL(q.data)
    setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [q.data])
  return url
}

const kb = (n: number) => (n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1000))} KB`)

/** Receipts and bills on a transaction: add from files or the camera, view, remove. */
export function AttachmentsField({ files, onChange, readOnly, maxSize }: { files: FileDraft[]; onChange: (f: FileDraft[]) => void; readOnly?: boolean; maxSize?: number }) {
  const l = useLedger()
  const pick = useRef<HTMLInputElement>(null)
  const camera = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(0)
  const [viewing, setViewing] = useState<FileDraft | null>(null)
  const shown = files.filter((f) => !f.removed)
  const limit = maxSize ?? 10 << 20
  const add = async (list: FileList | null) => {
    if (!list?.length) return
    const added: FileDraft[] = []
    setBusy(list.length)
    for (const f of Array.from(list)) {
      try {
        const body = await shrinkImage(f)
        if (body.size > limit) { toast(`${f.name} is larger than ${kb(limit)}`); continue }
        const r = await api.uploadBlob(l.id, body)
        const name = body === f ? f.name : f.name.replace(/\.(png|webp|jpe?g)$/i, '') + '.jpg'
        added.push({ id: uuidv7(), name: name.slice(0, 200) || 'receipt', mime: r.mime, size: r.size, sha256: r.sha256, existing: false })
      } catch (x) { toast(`${f.name}: ${errorText(x)}`) }
      setBusy((b) => b - 1)
    }
    setBusy(0)
    if (added.length) onChange([...files, ...added])
  }
  return (
    <div className="field">
      <span className="field-label">Receipts and bills</span>
      {shown.length > 0 && <ul className="attach-list">{shown.map((f) => (
        <li key={f.id}>
          <button type="button" className="attach-thumb" onClick={() => setViewing(f)} title={`Open ${f.name}`}>
            {f.mime.startsWith('image/') && f.mime !== 'image/heic' && f.mime !== 'image/heif' ? <Thumb sha={f.sha256} /> : <Icon name="file" size={22} />}
          </button>
          <div className="grow"><div className="attach-name">{f.name}</div><div className="sub">{kb(f.size)}{!f.existing && ' · saved with the transaction'}</div></div>
          <a className="icon-btn" href={api.blobUrl(l.id, f.sha256, f.name, true)} aria-label={`Download ${f.name}`} title="Download"><Icon name="download" size={16} /></a>
          {!readOnly && <button type="button" className="icon-btn" aria-label={`Remove ${f.name}`} onClick={() => onChange(files.map((x) => (x.id === f.id ? { ...x, removed: true } : x)))}><Icon name="x" size={16} /></button>}
        </li>
      ))}</ul>}
      {!readOnly && <div className="row">
        <button type="button" onClick={() => pick.current?.click()} disabled={busy > 0}>{busy ? <Spinner size={15} /> : <Icon name="paperclip" size={15} />} {busy ? `Uploading ${busy}…` : 'Attach file'}</button>
        <button type="button" className="only-phone" onClick={() => camera.current?.click()} disabled={busy > 0}><Icon name="camera" size={15} /> Take photo</button>
        <input ref={pick} type="file" hidden multiple accept="image/*,application/pdf" onChange={(e) => { void add(e.target.files); e.target.value = '' }} />
        <input ref={camera} type="file" hidden accept="image/*" capture="environment" onChange={(e) => { void add(e.target.files); e.target.value = '' }} />
      </div>}
      {shown.length === 0 && readOnly && <span className="muted small">None</span>}
      {!readOnly && <span className="field-hint">Photos or PDFs, up to {kb(limit)} each. Handy for warranties, reimbursements and tax proofs.</span>}
      {viewing && <Viewer file={viewing} onClose={() => setViewing(null)} />}
    </div>
  )
}

function Thumb({ sha }: { sha: string }) {
  const url = useBlobUrl(sha)
  return url ? <img src={url} alt="" /> : <Spinner size={16} />
}

function Viewer({ file, onClose }: { file: FileDraft; onClose: () => void }) {
  const l = useLedger()
  const url = useBlobUrl(file.sha256)
  const image = file.mime.startsWith('image/') && file.mime !== 'image/heic' && file.mime !== 'image/heif'
  return (
    <Modal title={file.name} onClose={onClose} wide footer={<>
      <a className="button" href={api.blobUrl(l.id, file.sha256, file.name, true)}><Icon name="download" size={15} /> Download</a>
      <span className="spacer" /><button type="button" onClick={onClose}>Close</button></>}>
      {!url ? <p className="muted"><Spinner size={15} /> Loading…</p> : image ? <img className="attach-full" src={url} alt={file.name} />
        : file.mime === 'application/pdf' ? <a className="button primary" href={url} target="_blank" rel="noreferrer"><Icon name="external" size={15} /> Open the PDF</a>
          : <p className="muted">This file cannot be shown here. Download it to open it.</p>}
    </Modal>
  )
}

const FIELD_LABEL: Record<string, string> = {
  amount: 'amount', to_amount: 'amount received', account_id: 'account', to_account_id: 'to account', category_id: 'category', payee: 'payee', note: 'note',
  local_date: 'date', status: 'status', tag_ids: 'tags', type: 'type', excluded_from_reports: 'excluded from reports',
}

/** "Who changed this, and what": the saved versions of a record, newest first, with what each changed. */
export function HistoryPanel({ entity, id, currency }: { entity: string; id: string; currency: string }) {
  const l = useLedger()
  const [open, setOpen] = useState(false)
  const cats = useCategoryIndex()
  const accounts = useAccounts()
  const hist = useQuery({ queryKey: [l.id, 'history', entity, id], queryFn: () => api.history(l.id, entity, id), enabled: open })
  const accName = (v: unknown) => (accounts.data ?? []).find((a) => a.id === v)?.name ?? '?'
  const show = (k: string, v: unknown): string => {
    if (v === null || v === undefined || v === '') return '—'
    if (k === 'amount' || k === 'to_amount') return formatMoney(Number(v), currency)
    if (k === 'category_id') return cats.path(String(v))
    if (k === 'account_id' || k === 'to_account_id') return accName(v)
    if (k === 'local_date') return shortDate(String(v))
    if (Array.isArray(v)) return `${v.length}`
    return String(v)
  }
  const changes = (e: HistoryEntry, prev?: HistoryEntry): string[] => {
    if (!prev) return [e.deleted ? 'Deleted' : 'Created']
    if (e.deleted && !prev.deleted) return ['Moved to Trash']
    if (!e.deleted && prev.deleted) return ['Restored from Trash']
    return Object.keys(FIELD_LABEL).filter((k) => JSON.stringify(e.data[k] ?? null) !== JSON.stringify(prev.data[k] ?? null))
      .map((k) => `${FIELD_LABEL[k]}: ${show(k, prev.data[k])} → ${show(k, e.data[k])}`)
  }
  const list = hist.data ?? []
  return (
    <div className="history">
      <button type="button" className="link small" onClick={() => setOpen(!open)} aria-expanded={open}><Icon name="history" size={14} /> {open ? 'Hide' : 'Show'} change history</button>
      {open && (hist.isPending ? <p className="muted small"><Spinner size={13} /> Loading…</p> : hist.isError ? <p className="error small">{errorText(hist.error)}</p>
        : list.length === 0 ? <p className="muted small">No recorded changes. History is kept for changes made after this feature was added to the server.</p> : (
          <ol className="history-list">{list.map((e, i) => {
            const what = changes(e, list[i + 1]).filter(Boolean)
            return (
              <li key={e.seq}>
                <div><strong>{e.user_name || e.user_email || 'Someone'}</strong> <span className="muted small">on {e.device || 'a device'} · {new Date(e.at).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}</span></div>
                <ul className="muted small">{(what.length ? what : ['Saved without visible changes']).map((t) => <li key={t}>{t}</li>)}</ul>
              </li>
            )
          })}</ol>
        ))}
    </div>
  )
}
