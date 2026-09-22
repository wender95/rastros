import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Usuario } from '../api/tipos'
import { useAuth } from '../auth/AuthContext'
import { Aviso } from '../componentes/Ui'

/**
 * Troca da própria senha.
 *
 * Com senha provisória (a de demonstração ou a que o administrador definiu) esta é a
 * única tela disponível — o servidor recusa o resto até a troca. Fora disso, fica no menu.
 */
export default function TrocarSenha({ obrigatoria = false }: { obrigatoria?: boolean }) {
  const { usuario, atualizarUsuario, sair } = useAuth()
  const navegar = useNavigate()
  const [atual, setAtual] = useState('')
  const [nova, setNova] = useState('')
  const [confirmacao, setConfirmacao] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [pronto, setPronto] = useState(false)

  const curta = nova.length > 0 && nova.length < 8
  const diferentes = confirmacao.length > 0 && nova !== confirmacao

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    if (nova !== confirmacao) {
      setErro('A confirmação não bate com a nova senha.')
      return
    }
    setErro(null)
    setEnviando(true)
    try {
      const atualizado = await api.post<Usuario>('/auth/trocar-senha', { senhaAtual: atual, novaSenha: nova })
      setPronto(true)
      setAtual('')
      setNova('')
      setConfirmacao('')
      atualizarUsuario(atualizado)
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível trocar a senha.')
    } finally {
      setEnviando(false)
    }
  }

  const formulario = (
    <form onSubmit={enviar}>
      {erro && <Aviso tipo="erro">{erro}</Aviso>}
      {pronto && !obrigatoria && <Aviso tipo="ok">Senha trocada.</Aviso>}
      <div className="campo">
        <label htmlFor="senha-atual">Senha atual</label>
        <input
          id="senha-atual"
          type="password"
          value={atual}
          autoComplete="current-password"
          onChange={(e) => setAtual(e.target.value)}
          required
        />
      </div>
      <div className="campo">
        <label htmlFor="senha-nova">Nova senha</label>
        <input
          id="senha-nova"
          type="password"
          value={nova}
          autoComplete="new-password"
          onChange={(e) => setNova(e.target.value)}
          required
        />
        <small className={curta ? 'dica-campo dica-erro' : 'dica-campo'}>Pelo menos 8 caracteres.</small>
      </div>
      <div className="campo">
        <label htmlFor="senha-confirmacao">Repita a nova senha</label>
        <input
          id="senha-confirmacao"
          type="password"
          value={confirmacao}
          autoComplete="new-password"
          onChange={(e) => setConfirmacao(e.target.value)}
          required
        />
        {diferentes && <small className="dica-campo dica-erro">As duas senhas estão diferentes.</small>}
      </div>
      <button className="botao botao-grande botao-bloco" disabled={enviando || curta || diferentes}>
        {enviando ? 'Salvando...' : 'Trocar senha'}
      </button>
    </form>
  )

  if (obrigatoria) {
    return (
      <div className="login-tela">
        <div className="login-caixa">
          <h1>Escolha sua senha</h1>
          <p className="subtitulo">
            {usuario?.nome}, você entrou com uma senha provisória. Antes de continuar, escolha
            uma senha só sua.
          </p>
          {formulario}
          <button className="botao botao-secundario botao-bloco espaco-topo" onClick={sair}>
            Sair
          </button>
        </div>
      </div>
    )
  }

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>Trocar senha</h1>
          <p>A senha vale para todas as telas do sistema.</p>
        </div>
      </div>
      <div className="cartao cartao-estreito">
        {formulario}
        <button className="botao botao-secundario espaco-topo" onClick={() => navegar(-1)}>
          Voltar
        </button>
      </div>
    </>
  )
}
