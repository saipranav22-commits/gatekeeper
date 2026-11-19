/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{ts,tsx,js,jsx}",
  ],
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: {
        "2xl": "1400px",
      },
    },
    extend: {
      colors: {
        // Obsidian Titanium semantic tokens
        canvas: {
          DEFAULT: "var(--bg-canvas)",
        },
        surface: {
          DEFAULT: "var(--bg-surface)",
          elevated: "var(--bg-surface-elevated)",
          hover: "var(--bg-surface-hover)",
        },
        moss: {
          DEFAULT: "var(--color-moss)",
          subtle: "var(--color-moss-subtle)",
          border: "var(--border-strong)",
        },
        titanium: {
          DEFAULT: "var(--color-titanium)",
          muted: "var(--color-titanium-muted)",
        },
        emerald: {
          DEFAULT: "var(--color-emerald)",
          hover: "var(--color-emerald-hover)",
          active: "var(--color-emerald-active)",
        },
        mint: "var(--color-mint)",
        'silver-frost': "var(--color-silver-frost)",
        'signal-red': {
          DEFAULT: "var(--color-signal-red)",
          subtle: "var(--color-signal-red-subtle)",
        },
        'warning-amber': {
          DEFAULT: "var(--color-warning-amber)",
          subtle: "var(--color-warning-amber-subtle)",
        },
        // Slate mapping to Obsidian Titanium tonal scale
        slate: {
          50: '#131A18',
          100: '#1A2421',
          200: '#22382D',
          300: '#375B48',
          400: '#5C6865',
          500: '#899693',
          600: '#A4B0AD',
          700: '#C2CCC8',
          800: '#DCE4E1',
          900: '#F4FAF7',
          950: '#070A09',
        },
        // Indigo mapping to Electric Emerald system accents
        indigo: {
          50: 'rgba(49, 233, 154, 0.1)',
          100: 'rgba(49, 233, 154, 0.15)',
          200: 'rgba(49, 233, 154, 0.25)',
          300: '#B8F3D6',
          400: '#5EEBB0',
          500: '#31E99A',
          600: '#28C984',
          700: '#1FA36A',
          800: '#1E3429',
          900: '#131A18',
          950: '#070A09',
        },
        // Compatibility bindings
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        sidebar: {
          DEFAULT: "#070A09",
          surface: "#131A18",
          foreground: "#DCE4E1",
          muted: "#899693",
          border: "#375B48",
          active: "#1A2421",
          hover: "#1A2421",
        }
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'Cascadia Code', 'ui-monospace', 'monospace'],
      },
      boxShadow: {
        'emerald-sm': '0 0 10px rgba(49, 233, 154, 0.2)',
        'emerald-glow': '0 0 20px rgba(49, 233, 154, 0.25)',
        'red-glow': '0 0 20px rgba(240, 84, 96, 0.25)',
        'card-dark': '0 4px 20px -2px rgba(0, 0, 0, 0.6)',
        'modal-dark': '0 10px 40px -4px rgba(0, 0, 0, 0.8), 0 0 1px 1px rgba(55, 91, 72, 0.6)',
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
    },
  },
  plugins: [],
}
