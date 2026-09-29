import type { SetorNome, Usuario } from '../api/tipos'

/**
 * Quem trabalha em mais de um setor (ex.: Recorte e Frota) escolhe em "Meu setor" em qual
 * está agora. A escolha vai em cada pedido ao servidor (cabeçalho X-Setor), que só a aceita
 * se o setor for mesmo da pessoa.
 */
const CHAVE = 'rastros.setor'

export function lerSetorAtivo(): number | null {
  try {
    const valor = Number(localStorage.getItem(CHAVE))
    return Number.isFinite(valor) && valor > 0 ? valor : null
  } catch {
    return null
  }
}

export function escolherSetor(setorId: number) {
  try {
    localStorage.setItem(CHAVE, String(setorId))
  } catch {
    /* sem armazenamento: fica o setor principal */
  }
}

/** Ao entrar, sair ou agir como outra pessoa, a escolha antiga não vale mais. */
export function esquecerSetorAtivo() {
  try {
    localStorage.removeItem(CHAVE)
  } catch {
    /* nada a esquecer */
  }
}

/** Todos os setores da pessoa, o principal primeiro. */
export function setoresDe(usuario: Usuario): { id: number; nome: SetorNome }[] {
  if (usuario.setores?.length) return usuario.setores
  return usuario.setor && usuario.setorId ? [{ id: usuario.setorId, nome: usuario.setor }] : []
}

/**
 * Os setores que aparecem em "Meu setor". A Frota de quem tem coluna na agenda não entra:
 * o adesivador trabalha pela Minha agenda (o projeto é que move a OS dentro da Frota).
 */
export function setoresDeTrabalho(usuario: Usuario) {
  return setoresDe(usuario).filter((s) => !(s.nome === 'FROTA' && usuario.temAgenda))
}

/** O setor em que a pessoa está agora: o escolhido, se for dela; senão o principal. */
export function setorEmUso(usuario: Usuario) {
  const setores = setoresDe(usuario)
  const escolhido = lerSetorAtivo()
  return setores.find((s) => s.id === escolhido) ?? setores[0] ?? null
}
