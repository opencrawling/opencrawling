/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        background: '#020617', // Slate-950
        foreground: '#f8fafc', // Slate-50
        card: {
          DEFAULT: '#0f172a', // Slate-900
          foreground: '#f8fafc',
        },
        popover: {
          DEFAULT: '#020617',
          foreground: '#f8fafc',
        },
        primary: {
          DEFAULT: '#06b6d4', // Cyan-500
          foreground: '#000000',
        },
        secondary: {
          DEFAULT: '#1e293b', // Slate-800
          foreground: '#f8fafc',
        },
        muted: {
          DEFAULT: '#94a3b8', // Slate-400 for high readability of text-muted
          foreground: '#94a3b8', // Slate-400
        },
        accent: {
          DEFAULT: '#3b82f6', // Blue-500
          foreground: '#f8fafc',
        },
        destructive: {
          DEFAULT: '#ef4444',
          foreground: '#f8fafc',
        },
        border: '#1e293b',
        input: '#1e293b',
        ring: '#06b6d4',
      },
      borderRadius: {
        lg: '0.5rem',
        md: 'calc(0.5rem - 2px)',
        sm: 'calc(0.5rem - 4px)',
      },
    },
  },
  plugins: [],
}
