import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, aoExigirTrocaDeSenha, aoPerderSessao, tokenStorage } from '../api/client'
import type { LoginResponse, Usuario } from '../api/tipos'

interface AuthContextValue {
  usuario: Usuario | null
  carregando: boolean
  entrar: (usuario: string, senha: string) => Promise<void>
  sair: () => void
  /** Depois de trocar a senha: o servidor devolve o usuario sem a marca de provisoria. */
  atualizarUsuario: (usuario: Usuario) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [usuario, setUsuario] = useState<Usuario | null>(null)
  const [carregando, setCarregando] = useState(true)

  const sair = useCallback(() => {
    tokenStorage.limpar()
    setUsuario(null)
  }, [])

  useEffect(() => aoPerderSessao(() => setUsuario(null)), [])
  useEffect(
    () => aoExigirTrocaDeSenha(() => setUsuario((atual) => (atual ? { ...atual, trocarSenha: true } : atual))),
    [],
  )

  useEffect(() => {
    if (!tokenStorage.ler()) {
      setCarregando(false)
      return
    }
    api
      .get<Usuario>('/auth/me')
      .then(setUsuario)
      .catch(() => tokenStorage.limpar())
      .finally(() => setCarregando(false))
  }, [])

  const entrar = useCallback(async (usuario: string, senha: string) => {
    const resposta = await api.post<LoginResponse>('/auth/login', { usuario, senha })
    tokenStorage.gravar(resposta.token)
    setUsuario(resposta.usuario)
  }, [])

  const valor = useMemo(
    () => ({ usuario, carregando, entrar, sair, atualizarUsuario: setUsuario }),
    [usuario, carregando, entrar, sair],
  )

  return <AuthContext.Provider value={valor}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth precisa estar dentro de AuthProvider')
  return ctx
}
