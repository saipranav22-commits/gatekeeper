import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { CommandPalette } from '@/components/navigation/CommandPalette'
import { AuthProvider } from '@/context/AuthContext'

const mockNavigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

describe('CommandPalette Component', () => {
  const renderPalette = (isOpen = true, onClose = vi.fn()) => {
    return render(
      <AuthProvider>
        <MemoryRouter>
          <CommandPalette isOpen={isOpen} onClose={onClose} />
        </MemoryRouter>
      </AuthProvider>
    )
  }

  it('does not render dialog when isOpen is false', () => {
    renderPalette(false)
    expect(screen.queryByRole('dialog', { name: /command palette/i })).not.toBeInTheDocument()
  })

  it('renders search input and commands when isOpen is true', () => {
    renderPalette(true)
    expect(screen.getByRole('dialog', { name: /command palette/i })).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/search commands/i)).toBeInTheDocument()
    expect(screen.getByText(/Command Center & Overview/i)).toBeInTheDocument()
    expect(screen.getByText(/Consumers & Developers/i)).toBeInTheDocument()
    expect(screen.getByText(/API Keys & Secrets/i)).toBeInTheDocument()
  })

  it('filters commands according to search query', () => {
    renderPalette(true)
    const input = screen.getByPlaceholderText(/search commands/i)

    fireEvent.change(input, { target: { value: 'consumer' } })

    expect(screen.getByText(/Consumers & Developers/i)).toBeInTheDocument()
    expect(screen.getByText(/Register New Consumer/i)).toBeInTheDocument()
    expect(screen.queryByText(/Command Center & Overview/i)).not.toBeInTheDocument()
  })

  it('shows empty message when no commands match query', () => {
    renderPalette(true)
    const input = screen.getByPlaceholderText(/search commands/i)

    fireEvent.change(input, { target: { value: 'nonexistent-query-xyz' } })

    expect(screen.getByText(/No matching commands or routes found/i)).toBeInTheDocument()
  })

  it('executes command and triggers onClose when clicked', () => {
    const onClose = vi.fn()
    renderPalette(true, onClose)

    const consumerCmd = screen.getByText(/Consumers & Developers/i)
    fireEvent.click(consumerCmd)

    expect(mockNavigate).toHaveBeenCalledWith('/consumers')
    expect(onClose).toHaveBeenCalled()
  })

  it('closes command palette when Escape key is pressed', () => {
    const onClose = vi.fn()
    renderPalette(true, onClose)

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
