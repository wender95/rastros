import { lerSetorAtivo } from '../auth/setorAtivo'

const CHAVE_TOKEN = 'rastros.token'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly detalhes?: Record<string, string>,
  ) {
    super(message)
  }
}

/** Antes da troca de nome o login ficava nesta chave: quem já estava dentro continua. */
const CHAVE_TOKEN_ANTIGA = 'ostracker.token'

export const tokenStorage = {
  ler: () => {
    const antigo = localStorage.getItem(CHAVE_TOKEN_ANTIGA)
    if (antigo) {
      if (!localStorage.getItem(CHAVE_TOKEN)) localStorage.setItem(CHAVE_TOKEN, antigo)
      localStorage.removeItem(CHAVE_TOKEN_ANTIGA)
    }
    return localStorage.getItem(CHAVE_TOKEN)
  },
  gravar: (token: string) => localStorage.setItem(CHAVE_TOKEN, token),
  limpar: () => localStorage.removeItem(CHAVE_TOKEN),
}

/** Disparado quando o backend recusa o token; o AuthProvider escuta e derruba a sessao. */
const EVENTO_NAO_AUTORIZADO = 'rastros:nao-autorizado'
/** Disparado quando o backend exige a troca da senha provisoria antes de qualquer outra coisa. */
const EVENTO_TROCAR_SENHA = 'rastros:trocar-senha'
export const aoExigirTrocaDeSenha = (handler: () => void) => {
  window.addEventListener(EVENTO_TROCAR_SENHA, handler)
  return () => window.removeEventListener(EVENTO_TROCAR_SENHA, handler)
}
export const aoPerderSessao = (handler: () => void) => {
  window.addEventListener(EVENTO_NAO_AUTORIZADO, handler)
  return () => window.removeEventListener(EVENTO_NAO_AUTORIZADO, handler)
}

async function requisicao<T>(caminho: string, init: RequestInit = {}): Promise<T> {
  const token = tokenStorage.ler()
  const setor = lerSetorAtivo()
  const resposta = await fetch(`/api${caminho}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      // Quem é de mais de um setor: o escolhido em Meu setor (o servidor confere se é dele).
      ...(setor ? { 'X-Setor': String(setor) } : {}),
      ...(init.headers ?? {}),
    },
  })

  if (resposta.status === 401) {
    tokenStorage.limpar()
    window.dispatchEvent(new Event(EVENTO_NAO_AUTORIZADO))
    throw new ApiError(401, 'Sessao expirada. Entre novamente.')
  }

  if (resposta.status === 204) return undefined as T

  const corpo = await resposta.text()
  const dados = corpo ? JSON.parse(corpo) : null

  if (!resposta.ok) {
    if (resposta.status === 403 && dados?.codigo === 'TROCAR_SENHA') {
      window.dispatchEvent(new Event(EVENTO_TROCAR_SENHA))
    }
    throw new ApiError(resposta.status, dados?.erro ?? 'Nao foi possivel completar a acao.', dados?.detalhes)
  }
  return dados as T
}

export const api = {
  get: <T,>(caminho: string) => requisicao<T>(caminho),
  post: <T,>(caminho: string, corpo?: unknown) =>
    requisicao<T>(caminho, { method: 'POST', body: JSON.stringify(corpo ?? {}) }),
  put: <T,>(caminho: string, corpo: unknown) =>
    requisicao<T>(caminho, { method: 'PUT', body: JSON.stringify(corpo) }),
  patch: <T,>(caminho: string, corpo: unknown) =>
    requisicao<T>(caminho, { method: 'PATCH', body: JSON.stringify(corpo) }),
  delete: <T,>(caminho: string) => requisicao<T>(caminho, { method: 'DELETE' }),
}
