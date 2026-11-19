import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { BrowserRouter } from 'react-router-dom'
import { NotFound } from '@/pages/NotFound'

describe('NotFound 404 Component', () => {
  it('renders 404 header and description in Obsidian Titanium theme', () => {
    render(
      <BrowserRouter>
        <NotFound />
      </BrowserRouter>
    )

    expect(screen.getByRole('heading', { level: 1, name: '404' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Route Not Found' })).toBeInTheDocument()
    expect(
      screen.getByText(/The route you are trying to access does not exist on the Gatekeeper administrative console/i)
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Back to Overview/i })).toHaveAttribute('href', '/')
  })
})
