import type { ReactElement } from 'react'
import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { pode, rotuloPerfil, telaInicial } from './auth/acessos'
import type { Area } from './auth/acessos'
import { useAuth } from './auth/AuthContext'
import { Carregando, rotuloSetor } from './componentes/Ui'
import Admin from './paginas/Admin'
import Agenda from './paginas/Agenda'
import Consulta from './paginas/Consulta'
import FluxoDetalhe from './paginas/FluxoDetalhe'
import Login from './paginas/Login'
import MinhaAgenda from './paginas/MinhaAgenda'
import Movimentar from './paginas/Movimentar'
import NovaOrdem from './paginas/NovaOrdem'
import OrdemDetalhe from './paginas/OrdemDetalhe'
import PatioPrateleira from './paginas/PatioPrateleira'
import Painel from './paginas/Painel'
import Produtividade from './paginas/Produtividade'
import RelatorioSemanal from './paginas/RelatorioSemanal'
import TrocarSenha from './paginas/TrocarSenha'

/** Itens do menu, na ordem em que aparecem; cada um só para quem tem a área. */
const MENU: { area: Area; rota: string; rotulo: string }[] = [
  { area: 'movimentar', rota: '/movimentar', rotulo: 'Meu setor' },
  { area: 'minhaAgenda', rota: '/minha-agenda', rotulo: 'Minha agenda' },
  { area: 'painel', rota: '/painel', rotulo: 'Painel' },
  { area: 'agenda', rota: '/agenda', rotulo: 'Agenda' },
  { area: 'produtividade', rota: '/produtividade', rotulo: 'Produtividade' },
  { area: 'relatorio', rota: '/relatorio-semanal', rotulo: 'Relatório' },
  { area: 'patioPrateleira', rota: '/patio-prateleira', rotulo: 'Pátio e prateleira' },
  { area: 'consulta', rota: '/consulta', rotulo: 'Consultar OS' },
  { area: 'criarOs', rota: '/ordens/nova', rotulo: 'Abrir OS' },
]

export default function App() {
  const { usuario, carregando, sair } = useAuth()

  if (carregando) return <Carregando texto="Carregando sessão..." />
  if (!usuario) return <Login />
  // Senha provisoria: nada alem da troca. O servidor tambem recusa o resto.
  if (usuario.trocarSenha) return <TrocarSenha obrigatoria />

  const inicio = telaInicial(usuario)
  /** A tela, se o perfil tem a área; senão volta para a tela inicial dele. */
  const so = (area: Area, tela: ReactElement) => (pode(usuario, area) ? tela : <Navigate to={inicio} replace />)
  const administra = pode(usuario, 'administracao')

  return (
    <div className="app">
      <header className="topo">
        <div className="topo-interno">
          <div className="marca">
            OS <span>Tracker</span>
          </div>

          <nav className="nav">
            {MENU.filter((m) => pode(usuario, m.area)).map((m) => (
              <NavLink key={m.rota} to={m.rota} className={({ isActive }) => (isActive ? 'ativo' : '')}>
                {m.rotulo}
              </NavLink>
            ))}
            {pode(usuario, 'usuarios') && (
              <NavLink to="/admin" className={({ isActive }) => (isActive ? 'ativo' : '')}>
                {administra ? 'Administração' : 'Usuários'}
              </NavLink>
            )}
          </nav>

          <div className="usuario-topo">
            <div>
              <strong>{usuario.nome}</strong>
              <small>
                {rotuloPerfil(usuario.perfil)}
                {usuario.setor ? ` · ${rotuloSetor(usuario.setor)}` : ''}
              </small>
            </div>
            <NavLink to="/trocar-senha" className="link-topo">
              Trocar senha
            </NavLink>
            <button className="botao botao-secundario" onClick={sair}>
              Sair
            </button>
          </div>
        </div>
      </header>

      <main className="conteudo">
        <Routes>
          <Route path="/" element={<Navigate to={inicio} replace />} />
          <Route path="/movimentar" element={so('movimentar', <Movimentar />)} />
          <Route path="/fila" element={<Navigate to={inicio} replace />} />
          <Route path="/minha-agenda" element={so('minhaAgenda', <MinhaAgenda />)} />
          <Route path="/painel" element={so('painel', <Painel />)} />
          <Route path="/agenda" element={so('agenda', <Agenda />)} />
          <Route path="/produtividade" element={so('produtividade', <Produtividade />)} />
          <Route path="/relatorio-semanal" element={so('relatorio', <RelatorioSemanal />)} />
          <Route path="/patio-prateleira" element={so('patioPrateleira', <PatioPrateleira />)} />
          <Route path="/consulta" element={so('consulta', <Consulta />)} />
          <Route path="/ordens/nova" element={so('criarOs', <NovaOrdem />)} />
          <Route path="/ordens/:id" element={so('consulta', <OrdemDetalhe />)} />
          <Route path="/fluxos/:id" element={so('consulta', <FluxoDetalhe />)} />
          <Route path="/admin" element={so('usuarios', <Admin completo={administra} />)} />
          <Route path="/trocar-senha" element={<TrocarSenha />} />
          <Route path="*" element={<Navigate to={inicio} replace />} />
        </Routes>
      </main>
    </div>
  )
}
