import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Em desenvolvimento a tela roda no Vite e a API continua no Spring: o proxy manda
 * /api para o backend. O endereço vem de `VITE_API_URL` — na variável de ambiente ou
 * num `.env.local` da máquina — para poder apontar para uma cópia do sistema em outra
 * porta, sem mexer no que está em produção.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
