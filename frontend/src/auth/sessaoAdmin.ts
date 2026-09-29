/**
 * A sessão do administrador guardada enquanto ele age como outra pessoa (fase de teste).
 * Fica à parte do resto porque o login e a tela de "agir como" usam os dois.
 */

const CHAVE_ADMIN = 'rastros.token.admin'

export function lerSessaoAdmin(): string | null {
  try {
    return localStorage.getItem(CHAVE_ADMIN)
  } catch {
    return null
  }
}

export function guardarSessaoAdmin(token: string) {
  localStorage.setItem(CHAVE_ADMIN, token)
}

/** Ao sair do sistema (ou ao voltar para o admin), a sessão guardada sai junto. */
export function esquecerSessaoAdmin() {
  try {
    localStorage.removeItem(CHAVE_ADMIN)
  } catch {
    /* sem armazenamento: nada a esquecer */
  }
}
