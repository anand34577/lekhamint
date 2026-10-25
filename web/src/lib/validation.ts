// Form validation shown inside the page instead of the browser's pop-up bubble.
//
// Browsers check `required`, `minLength`, `type="email"` and so on when a form is
// submitted, and report the first problem in a native bubble. This module catches
// those reports, stops the bubble, and shows the message under the field in the
// app's own style, focusing the first field that needs attention.

type Field = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement

/** The plain-words message for a field the browser rejected. */
export function messageFor(el: Field): string {
  const v = el.validity
  const kind = el instanceof HTMLInputElement ? el.type : ''
  if (v.customError) return el.validationMessage
  if (v.valueMissing) {
    if (el.closest('.dp')) return 'Choose a date'
    if (kind === 'email') return 'Enter your email address'
    if (kind === 'password') return 'Enter a password'
    if (el instanceof HTMLSelectElement) return 'Choose an option'
    return 'This field is required'
  }
  if (v.typeMismatch) return kind === 'email' ? 'Enter a valid email address, like name@example.com' : 'Enter a valid value'
  if (v.tooShort) return `Use at least ${(el as HTMLInputElement).minLength} characters`
  if (v.tooLong) return `Use at most ${(el as HTMLInputElement).maxLength} characters`
  if (v.rangeUnderflow) return `Must be at least ${(el as HTMLInputElement).min}`
  if (v.rangeOverflow) return `Must be at most ${(el as HTMLInputElement).max}`
  if (v.patternMismatch) return el.title || 'Check the format of this value'
  if (v.badInput) return 'Enter a number'
  return el.validationMessage || 'Check this field'
}

const host = (el: Field): HTMLElement => el.closest<HTMLElement>('.field') ?? el.parentElement ?? el

function show(el: Field) {
  const h = host(el)
  h.classList.add('invalid')
  el.setAttribute('aria-invalid', 'true')
  let note = h.querySelector<HTMLElement>(':scope > .field-error.auto')
  if (!note) {
    note = document.createElement('span')
    note.className = 'field-error auto'
    note.setAttribute('role', 'alert')
    h.appendChild(note)
  }
  note.textContent = messageFor(el)
}

function clear(el: Field) {
  const h = host(el)
  h.classList.remove('invalid')
  el.removeAttribute('aria-invalid')
  h.querySelector(':scope > .field-error.auto')?.remove()
}

/** Call once at start-up. */
export function installFormValidation() {
  let focused = false
  document.addEventListener('invalid', (e) => {
    const el = e.target as Field
    e.preventDefault() // no browser bubble
    show(el)
    if (!focused) {
      focused = true
      // The date picker keeps a hidden input for validity; send focus to the visible one.
      const visible = el.closest('.dp')?.querySelector<HTMLElement>('input:not(.proxy)') ?? el
      visible.focus()
      visible.scrollIntoView({ block: 'center', behavior: 'smooth' })
      queueMicrotask(() => { focused = false })
    }
  }, true)
  const reset = (e: Event) => {
    const el = e.target as Field
    if (el?.closest?.('.field.invalid') || el?.getAttribute?.('aria-invalid')) clear(el)
  }
  document.addEventListener('input', reset, true)
  document.addEventListener('change', reset, true)
}
