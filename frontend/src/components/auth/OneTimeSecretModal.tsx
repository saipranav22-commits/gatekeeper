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
        <div className="flex items-start gap-3 p-3.5 bg-warning-amber/10 border border-warning-amber/30 rounded-lg text-warning-amber text-xs">
          <ShieldAlert className="w-5 h-5 text-warning-amber shrink-0 mt-0.5" />
          <div className="space-y-1">
            <p className="font-semibold text-warning-amber">Store this key in a secure location now</p>
            <p className="text-warning-amber/80 leading-relaxed">
              For security, Gatekeeper only stores the salted SHA-256 digest of this key. <strong>You will never be able to view or retrieve this plaintext value again.</strong> If you lose this key, you must revoke it and generate a new one.
            </p>
          </div>
        </div>

        {/* Metadata summary */}
        <div className="p-3 bg-surface-elevated border border-moss-subtle rounded-lg text-xs space-y-1.5 text-titanium">
          <div className="flex justify-between">
            <span className="font-medium text-titanium">Key Label:</span>
            <span className="font-mono text-silver-frost">{keyName}</span>
          </div>
          <div className="flex justify-between">
            <span className="font-medium text-titanium">Key Prefix:</span>
            <span className="font-mono text-silver-frost">{keyPrefix}</span>
          </div>
        </div>

        {/* Plaintext Secret Display */}
        <div className="space-y-1.5">
          <label className="text-xs font-semibold uppercase tracking-wider text-titanium flex items-center gap-1.5">
            <KeyRound className="w-3.5 h-3.5 text-emerald" />
            Plaintext API Key
          </label>
          <div className="relative">
            <input
              type="text"
              readOnly
              value={plaintextKey}
              onFocus={(e) => e.target.select()}
              className="w-full font-mono text-xs px-3.5 py-2.5 bg-black text-emerald border border-moss/60 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald/50 select-all pr-24 tracking-wider"
              aria-label="Plaintext API Key"
            />
            <button
              type="button"
              onClick={handleCopy}
              className="absolute right-1.5 top-1.5 bottom-1.5 px-3 bg-surface-elevated hover:bg-surface-hover active:bg-moss text-silver-frost rounded-md text-xs font-medium flex items-center gap-1.5 transition-colors cursor-pointer border border-moss-subtle"
              aria-label="Copy key to clipboard"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald" />
                  <span className="text-emerald">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5 text-titanium" />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>
          {copyError && (
            <p className="text-xs text-warning-amber mt-1">
              Clipboard access restricted by browser. Please manually highlight and copy the key above.
            </p>
          )}
        </div>

        {/* Dismissal button */}
        <div className="pt-3 border-t border-moss-subtle flex justify-end">
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
