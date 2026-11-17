import React, { useState } from 'react'
import { Modal } from '@/components/ui/Modal'
import { Button } from '@/components/ui/Button'
import { Check, Copy, ShieldAlert, KeyRound } from 'lucide-react'

interface OneTimeSecretModalProps {
  isOpen: boolean
  onClose: () => void
  keyName: string
  keyPrefix: string
  plaintextKey: string | null
}

export const OneTimeSecretModal: React.FC<OneTimeSecretModalProps> = ({
  isOpen,
  onClose,
  keyName,
  keyPrefix,
  plaintextKey,
}) => {
  const [copied, setCopied] = useState<boolean>(false)
  const [copyError, setCopyError] = useState<boolean>(false)

  const handleDismiss = () => {
    setCopied(false)
    setCopyError(false)
    onClose()
  }

  const handleCopy = async () => {
    if (!plaintextKey) return
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(plaintextKey)
        setCopied(true)
        setTimeout(() => setCopied(false), 3000)
      } else {
        throw new Error('Clipboard API not available')
      }
    } catch {
      // Fallback: select the manual input
      setCopyError(true)
    }
  }

  // Prevent rendering if plaintextKey is missing
  if (!isOpen || !plaintextKey) return null

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleDismiss}
      title="Save Your API Key"
      description="Cryptographic credential generated successfully"
      maxWidth="lg"
    >
      <div className="space-y-4">
        {/* Warning banner */}
        <div className="flex items-start gap-3 p-3.5 bg-amber-50 border border-amber-200/90 rounded-lg text-amber-900 text-xs">
          <ShieldAlert className="w-5 h-5 text-amber-600 shrink-0 mt-0.5" />
          <div className="space-y-1">
            <p className="font-semibold text-amber-950">Store this key in a secure location now</p>
            <p className="text-amber-800 leading-relaxed">
              For security, Gatekeeper only stores the salted SHA-256 digest of this key. <strong>You will never be able to view or retrieve this plaintext value again.</strong> If you lose this key, you must revoke it and generate a new one.
            </p>
          </div>
        </div>

        {/* Metadata summary */}
        <div className="p-3 bg-slate-50 border border-slate-200 rounded-lg text-xs space-y-1 text-slate-600">
          <div className="flex justify-between">
            <span className="font-medium text-slate-700">Key Label:</span>
            <span className="font-mono text-slate-900">{keyName}</span>
          </div>
          <div className="flex justify-between">
            <span className="font-medium text-slate-700">Key Prefix:</span>
            <span className="font-mono text-slate-900">{keyPrefix}</span>
          </div>
        </div>

        {/* Plaintext Secret Display */}
        <div className="space-y-1.5">
          <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 flex items-center gap-1.5">
            <KeyRound className="w-3.5 h-3.5 text-indigo-600" />
            Plaintext API Key
          </label>
          <div className="relative">
            <input
              type="text"
              readOnly
              value={plaintextKey}
              onFocus={(e) => e.target.select()}
              className="w-full font-mono text-xs px-3.5 py-2.5 bg-slate-900 text-emerald-400 border border-slate-800 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500/50 select-all pr-24 tracking-wider"
              aria-label="Plaintext API Key"
            />
            <button
              type="button"
              onClick={handleCopy}
              className="absolute right-1.5 top-1.5 bottom-1.5 px-3 bg-slate-800 hover:bg-slate-700 active:bg-slate-600 text-slate-200 rounded-md text-xs font-medium flex items-center gap-1.5 transition-colors"
              aria-label="Copy key to clipboard"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-400" />
                  <span className="text-emerald-400">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>
          {copyError && (
            <p className="text-xs text-amber-700 mt-1">
              Clipboard access restricted by browser. Please manually highlight and copy the key above.
            </p>
          )}
        </div>

        {/* Dismissal button */}
        <div className="pt-3 border-t border-slate-100 flex justify-end">
          <Button
            variant="primary"
            onClick={handleDismiss}
            className="w-full sm:w-auto"
          >
            I Have Securely Saved This Key
          </Button>
        </div>
      </div>
    </Modal>
  )
}
