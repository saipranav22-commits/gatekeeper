import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { OneTimeSecretModal } from '@/components/auth/OneTimeSecretModal'

describe('OneTimeSecretModal (Secret Lifecycle)', () => {
  it('renders plaintext key value and strict security warning', () => {
    const handleClose = vi.fn()

    render(
      <OneTimeSecretModal
        isOpen={true}
        onClose={handleClose}
        keyName="Production Key"
        keyPrefix="gk_live_12345678"
        plaintextKey="gk_live_12345678_abcdefghijklmnopqrstuvwxyz12345"
      />
    )

    // Security warning
    expect(screen.getByText(/Store this key in a secure location now/i)).toBeInTheDocument()
    expect(screen.getByText(/never be able to view or retrieve this plaintext value again/i)).toBeInTheDocument()

    // Key details
    expect(screen.getByText('Production Key')).toBeInTheDocument()
    expect(screen.getByText('gk_live_12345678')).toBeInTheDocument()

    // Plaintext input
    const input = screen.getByLabelText(/Plaintext API Key/i) as HTMLInputElement
    expect(input.value).toBe('gk_live_12345678_abcdefghijklmnopqrstuvwxyz12345')
  })

  it('triggers clipboard copy when copy button is clicked', async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, {
      clipboard: {
        writeText: writeTextMock,
      },
    })

    render(
      <OneTimeSecretModal
        isOpen={true}
        onClose={vi.fn()}
        keyName="Test Key"
        keyPrefix="gk_test_1234"
        plaintextKey="secret_key_value"
      />
    )

    const copyBtn = screen.getByLabelText(/Copy key to clipboard/i)
    fireEvent.click(copyBtn)

    expect(writeTextMock).toHaveBeenCalledWith('secret_key_value')
  })

  it('invokes onClose when operator confirms key is saved', () => {
    const handleClose = vi.fn()

    render(
      <OneTimeSecretModal
        isOpen={true}
        onClose={handleClose}
        keyName="Test Key"
        keyPrefix="gk_test_1234"
        plaintextKey="secret_key_value"
      />
    )

    const confirmBtn = screen.getByText(/I Have Securely Saved This Key/i)
    fireEvent.click(confirmBtn)

    expect(handleClose).toHaveBeenCalledTimes(1)
  })

  it('does not render when isOpen is false or plaintextKey is null', () => {
    const { container } = render(
      <OneTimeSecretModal
        isOpen={false}
        onClose={vi.fn()}
        keyName="Test Key"
        keyPrefix="gk_test_1234"
        plaintextKey={null}
      />
    )
    expect(container.firstChild).toBeNull()
  })
})
