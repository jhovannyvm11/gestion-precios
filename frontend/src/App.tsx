import { useEffect, useRef, useState } from 'react'
import { CircleAlert, LoaderCircle, Pencil, Plus, RefreshCw, Search, Trash2, X } from 'lucide-react'
import { ApiFailure, priceApi, type PriceResult, type ProductRule, type ProductRuleDraft, type PreferredProvider } from './types'

const emptyDraft: ProductRuleDraft & { sku: string } = {
  sku: '',
  name: '',
  marginPercentage: 15,
  preferredProvider: 'AUTO',
  active: true,
}

function messageFor(error: unknown) {
  if (error instanceof ApiFailure && error.status === 404) return 'El SKU no existe o está inactivo.'
  if (error instanceof ApiFailure && error.status === 503) return 'Los proveedores no están disponibles y no hay un precio de respaldo.'
  return error instanceof Error ? error.message : 'Ocurrió un error inesperado.'
}

function scrollToElement(element: HTMLElement | null) {
  if (!element) return

  const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
  const top = element.getBoundingClientRect().top + window.scrollY - 16
  window.scrollTo({ top, behavior })
  element.focus({ preventScroll: true })
}

function App() {
  const [rules, setRules] = useState<ProductRule[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [editingSku, setEditingSku] = useState<string | null>(null)
  const [draft, setDraft] = useState(emptyDraft)
  const [pageError, setPageError] = useState('')
  const [notice, setNotice] = useState('')
  const [price, setPrice] = useState<PriceResult | null>(null)
  const [priceError, setPriceError] = useState('')
  const [queryingSku, setQueryingSku] = useState('')
  const formPanelRef = useRef<HTMLElement | null>(null)
  const monitorPanelRef = useRef<HTMLElement | null>(null)

  async function loadRules() {
    setLoading(true)
    try {
      setRules(await priceApi.listRules())
      setPageError('')
    } catch (error) {
      setPageError(messageFor(error))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadRules()
  }, [])

  useEffect(() => {
    if (!notice) return

    const timeoutId = window.setTimeout(() => setNotice(''), 4000)
    return () => window.clearTimeout(timeoutId)
  }, [notice])

  useEffect(() => {
    if (!formOpen) return

    scrollToElement(formPanelRef.current)
    formPanelRef.current
      ?.querySelector<HTMLInputElement | HTMLSelectElement>('input:not([type="checkbox"]), select')
      ?.focus({ preventScroll: true })
  }, [formOpen, editingSku])

  function startCreate() {
    setEditingSku(null)
    setDraft(emptyDraft)
    setFormOpen(true)
    setPageError('')
    setNotice('')
  }

  function startEdit(rule: ProductRule) {
    setEditingSku(rule.sku)
    setDraft({
      sku: rule.sku,
      name: rule.name,
      marginPercentage: rule.marginPercentage,
      preferredProvider: rule.preferredProvider,
      active: rule.active,
    })
    setFormOpen(true)
    setPageError('')
    setNotice('')
  }

  async function saveRule(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    setPageError('')
    setNotice('')
    const { sku, ...ruleDraft } = draft
    try {
      if (editingSku) {
        await priceApi.updateRule(editingSku, ruleDraft)
        setNotice(`Regla ${editingSku} actualizada; su precio en caché se invalidó.`)
        setPrice((current) => current?.sku === editingSku ? null : current)
        setPriceError('')
      } else {
        await priceApi.createRule({ sku: sku.trim(), ...ruleDraft })
        setNotice(`Regla ${sku.trim()} creada.`)
      }
      setFormOpen(false)
      await loadRules()
    } catch (error) {
      setPageError(messageFor(error))
    } finally {
      setSaving(false)
    }
  }

  async function deleteRule(rule: ProductRule) {
    if (!window.confirm(`¿Eliminar la regla ${rule.sku}?`)) return
    try {
      await priceApi.deleteRule(rule.sku)
      setNotice(`Regla ${rule.sku} eliminada y caché invalidada.`)
      setPrice((current) => current?.sku === rule.sku ? null : current)
      await loadRules()
    } catch (error) {
      setPageError(messageFor(error))
    }
  }

  async function queryPrice(sku: string) {
    setQueryingSku(sku)
    setPrice(null)
    setPriceError('')
    setPageError('')
    scrollToElement(monitorPanelRef.current)
    try {
      setPrice(await priceApi.getPrice(sku))
    } catch (error) {
      setPrice(null)
      setPriceError(messageFor(error))
    } finally {
      setQueryingSku('')
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="#inicio" aria-label="Precio Claro, inicio">
          <span className="brand-mark"><span /></span>
          <span>precio<span className="brand-light">claro</span></span>
        </a>
        <div className="topbar-meta"><span className="live-dot" /> OPERACIÓN DE PRECIOS <span className="topbar-divider" /> API v1</div>
      </header>

      <main id="inicio" className="workspace">
        <section className="intro-row">
          <div>
            <p className="eyebrow">CATÁLOGO · INTEGRACIONES</p>
            <h1>Reglas de producto</h1>
            <p className="intro-copy">Administra márgenes y consulta cotizaciones consolidadas en tiempo real.</p>
          </div>
          <button className="button button-primary" type="button" onClick={startCreate}>
            <Plus size={17} strokeWidth={2.2} /> Nueva regla
          </button>
        </section>

        {pageError && <div className="alert alert-error" role="alert"><CircleAlert size={18} />{pageError}</div>}
        {notice && <output className="alert alert-success">{notice}</output>}

        {formOpen && (
          <section className="form-panel" ref={formPanelRef} tabIndex={-1} aria-labelledby="form-title">
            <div className="panel-heading">
              <div><p className="eyebrow">CONFIGURACIÓN COMERCIAL</p><h2 id="form-title">{editingSku ? `Editar ${editingSku}` : 'Nueva regla'}</h2></div>
              <button className="icon-button" type="button" title="Cerrar formulario" aria-label="Cerrar formulario" onClick={() => setFormOpen(false)}><X size={18} /></button>
            </div>
            <form className="rule-form" onSubmit={saveRule}>
              {!editingSku && <label>SKU<input required maxLength={64} pattern="[A-Z0-9][A-Z0-9._\x2d]{0,63}" value={draft.sku} onChange={(event) => setDraft({ ...draft, sku: event.target.value.trim().toUpperCase() })} placeholder="ABC-123" /></label>}
              <label className="name-field">Nombre del producto<input required maxLength={160} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="Nombre visible" /></label>
              <label>Margen (%)<input required type="number" min="0" max="1000" step="0.01" value={draft.marginPercentage} onChange={(event) => setDraft({ ...draft, marginPercentage: Number(event.target.value) })} /></label>
              <label>Proveedor preferido<select value={draft.preferredProvider} onChange={(event) => setDraft({ ...draft, preferredProvider: event.target.value as PreferredProvider })}><option value="AUTO">Automático</option><option value="PROVIDER_A">Proveedor A</option><option value="PROVIDER_B">Proveedor B</option></select></label>
              <label className="active-control"><input type="checkbox" checked={draft.active} onChange={(event) => setDraft({ ...draft, active: event.target.checked })} /><span>Regla activa</span></label>
              <div className="form-actions"><button className="button button-quiet" type="button" onClick={() => setFormOpen(false)}>Cancelar</button><button className="button button-primary" type="submit" disabled={saving}>{saving && <LoaderCircle className="spin" size={16} />}{saving ? 'Guardando…' : 'Guardar regla'}</button></div>
            </form>
          </section>
        )}

        <section className="content-grid">
          <div className="catalog-section">
            <div className="section-heading">
              <div><h2>Catálogo</h2><span className="count-label">{rules.length} {rules.length === 1 ? 'regla' : 'reglas'}</span></div>
              <button className="icon-button refresh-button" type="button" title="Actualizar catálogo" aria-label="Actualizar catálogo" onClick={() => void loadRules()}><RefreshCw size={16} /></button>
            </div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>SKU / PRODUCTO</th><th>MARGEN</th><th>PROVEEDOR</th><th>ESTADO</th><th><span className="sr-only">Acciones</span></th></tr></thead>
                <tbody>
                  {loading && <tr><td className="empty-state" colSpan={5}>Cargando reglas…</td></tr>}
                  {!loading && rules.length === 0 && <tr><td className="empty-state" colSpan={5}>No hay reglas registradas. Crea una para iniciar.</td></tr>}
                  {!loading && rules.map((rule) => (
                    <tr key={rule.sku}>
                      <td><span className="sku-cell">{rule.sku}</span><span className="product-name">{rule.name}</span></td>
                      <td className="numeric-cell">{Number(rule.marginPercentage).toFixed(2)}%</td>
                      <td><span className="provider-label">{providerName(rule.preferredProvider)}</span></td>
                      <td><span className={`status-pill ${rule.active ? 'is-active' : 'is-inactive'}`}><span />{rule.active ? 'Activa' : 'Inactiva'}</span></td>
                      <td><div className="row-actions">
                        <button className="icon-button query-button" type="button" title={`Consultar precio de ${rule.sku}`} aria-label={`Consultar precio de ${rule.sku}`} disabled={!rule.active || queryingSku === rule.sku} onClick={() => void queryPrice(rule.sku)}>{queryingSku === rule.sku ? <LoaderCircle className="spin" size={16} /> : <Search size={16} />}</button>
                        <button className="icon-button" type="button" title={`Editar ${rule.sku}`} aria-label={`Editar ${rule.sku}`} onClick={() => startEdit(rule)}><Pencil size={15} /></button>
                        <button className="icon-button danger-action" type="button" title={`Eliminar ${rule.sku}`} aria-label={`Eliminar ${rule.sku}`} onClick={() => void deleteRule(rule)}><Trash2 size={15} /></button>
                      </div></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <aside className="monitor-panel" ref={monitorPanelRef} tabIndex={-1} aria-live="polite" aria-label="Monitor de precio">
            <div className="monitor-header"><div className="monitor-icon"><Search size={18} /></div><div><p className="eyebrow">CONSULTA EN VIVO</p><h2>Monitor de precio</h2></div></div>
            <p className="monitor-hint">Selecciona una regla activa del catálogo para solicitar su precio consolidado.</p>
            <PriceMonitorContent queryingSku={queryingSku} priceError={priceError} price={price} />
          </aside>
        </section>
        <footer className="page-footer"><span>PRECIO CLARO <span>·</span> SISTEMA DE GESTIÓN DE PRECIOS</span><span>PROVEEDORES A + B</span></footer>
      </main>
    </div>
  )
}

function providerName(provider: PreferredProvider) {
  if (provider === 'PROVIDER_A') return 'Proveedor A'
  if (provider === 'PROVIDER_B') return 'Proveedor B'
  return 'Automático'
}

function PriceMonitorContent({
  queryingSku,
  priceError,
  price,
}: Readonly<{ queryingSku: string; priceError: string; price: PriceResult | null }>) {
  if (queryingSku) {
    return <output className="monitor-loading"><LoaderCircle className="spin" size={19} />Consultando {queryingSku}…</output>
  }

  if (priceError) {
    return <div className="monitor-error" role="alert"><CircleAlert size={16} />{priceError}</div>
  }

  if (price) return <PriceBreakdown price={price} />

  return <div className="monitor-empty"><span className="empty-rings"><span /></span><span>Esperando consulta</span><small>El desglose aparecerá aquí</small></div>
}

function PriceBreakdown({ price }: Readonly<{ price: PriceResult }>) {
  return (
    <div className="price-result">
      <div className="result-topline"><div><span className="result-sku">{price.sku}</span><span className="result-name">{price.productName}</span></div><span className={`source-pill source-${price.source.toLowerCase().replace('_', '-')}`}>{price.source.replace('_', ' ')}</span></div>
      {price.stale && <div className="stale-alert"><CircleAlert size={15} />Cotización stale: se muestra el último valor disponible.</div>}
      <div className="price-total"><span>PRECIO FINAL</span><strong>{price.currency} {price.finalPrice.toFixed(2)}</strong></div>
      <dl className="price-details"><div><dt>Precio proveedor</dt><dd>{price.currency} {price.providerPrice.toFixed(2)}</dd></div><div><dt>Margen aplicado</dt><dd>{price.marginPercentage.toFixed(2)}%</dd></div><div><dt>Latencia</dt><dd>{price.responseTimeMs} ms</dd></div></dl>
    </div>
  )
}

export default App