/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useState, useEffect, useRef, type ReactNode } from 'react'
import {
  LayoutDashboard,
  PlayCircle,
  Plug2,
  Activity,
  Settings as SettingsIcon,
  Menu,
  X,
  Search,
  ChevronRight,
  Wand2,
  ArrowRightLeft,
  FileSpreadsheet,
  LogIn,
  LogOut,
  ShieldAlert
} from 'lucide-react'
import Dashboard from './components/Dashboard'
import JobTable from './components/JobTable'
import ConnectorForm from './components/ConnectorForm'
import Settings from './components/Settings'
import Narrativization from './components/Narrativization'
import McfMigration from './components/McfMigration'
import ReportDashboard from './components/ReportDashboard'
import { statusApi } from './lib/api'
import { useAuth } from './lib/useAuth'
import { initials, type Me } from './lib/auth'

type View = 'dashboard' | 'jobs' | 'connectors' | 'reports' | 'mcf-migration' | 'logs' | 'narrativization' | 'settings'

/** Which views a caller may open; the runtime enforces the same rules on the API. */
function canOpen(view: View, me: Me): boolean {
  switch (view) {
    case 'connectors':
    case 'narrativization':
      return me.canCreateJobs
    case 'mcf-migration':
    case 'logs':
    case 'settings':
      return me.admin
    default:
      return true
  }
}

function CenteredCard({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-screen items-center justify-center bg-background text-foreground p-4">
      <div className="card-container max-w-md w-full flex flex-col items-center gap-4 text-center">
        <div className="bg-primary p-3 rounded-lg">
          <Search className="w-7 h-7 text-primary-foreground" />
        </div>
        {children}
      </div>
    </div>
  )
}

export default function App() {
  const { status, me, error, signIn } = useAuth()

  if (status === 'loading') {
    return <CenteredCard><p className="text-muted text-sm">Loading…</p></CenteredCard>
  }
  if (status === 'misconfigured') {
    return (
      <CenteredCard>
        <ShieldAlert className="w-6 h-6 text-yellow-500" />
        <h1 className="text-lg font-bold">Sign-in is not set up</h1>
        <p className="text-muted text-sm">
          This runtime requires a token on its API, but no client is configured for the admin UI.
          Set <code>opencrawling.security.identity.admin-ui.client-id</code> to the identity provider's public client.
        </p>
      </CenteredCard>
    )
  }
  if (status === 'signed-out' || !me) {
    return (
      <CenteredCard>
        <h1 className="text-lg font-bold">OpenCrawling</h1>
        <p className="text-muted text-sm">Sign in with your organization's account to manage pipelines.</p>
        {error && <p className="text-red-400 text-xs break-words">Sign-in failed: {error}</p>}
        <button onClick={signIn} className="btn-primary flex items-center gap-2">
          <LogIn className="w-4 h-4" />
          Sign in
        </button>
      </CenteredCard>
    )
  }
  return <Console me={me} />
}

function Console({ me }: { me: Me }) {
  const { status, signOut } = useAuth()
  const [activeView, setActiveView] = useState<View>('dashboard')
  const [isSidebarOpen, setIsSidebarOpen] = useState(true)
  const [systemStatus, setSystemStatus] = useState<Record<string, string>>({
    postgres: 'DOWN',
    redis: 'DOWN',
    ollama: 'DOWN',
    system: 'UNKNOWN'
  })
  const [logs, setLogs] = useState<string[]>([])
  const logsEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const res = await statusApi.getSystemStatus()
        setSystemStatus(res.data)
      } catch (err) {
        console.error("Failed to fetch system status:", err)
        setSystemStatus({
          postgres: 'DOWN',
          redis: 'DOWN',
          ollama: 'DOWN',
          system: 'UNREACHABLE'
        })
      }
    }
    fetchStatus()
    const statusInterval = setInterval(fetchStatus, 5000)
    return () => clearInterval(statusInterval)
  }, [])

  useEffect(() => {
    if (activeView !== 'logs') return
    const fetchLogs = async () => {
      try {
        const res = await statusApi.getLogs()
        setLogs(res.data)
      } catch (err) {
        console.error("Failed to fetch logs:", err)
      }
    }
    fetchLogs()
    const logsInterval = setInterval(fetchLogs, 2000)
    return () => clearInterval(logsInterval)
  }, [activeView])

  useEffect(() => {
    if (activeView === 'logs') {
      logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [logs, activeView])

  const getLogLineColor = (line: string) => {
    if (line.includes("INFO:")) return "text-blue-400";
    if (line.includes("DEBUG:")) return "text-slate-500";
    if (line.includes("WARN:")) return "text-yellow-400";
    if (line.includes("SUCCESS:")) return "text-green-400";
    if (line.includes("ERROR:") || line.includes("FAILED:")) return "text-red-400";
    return "text-slate-300";
  }

  const menuItems = ([
    { id: 'dashboard',       label: 'Dashboard',              icon: LayoutDashboard },
    { id: 'jobs',            label: 'Job Management',         icon: PlayCircle },
    { id: 'connectors',      label: 'Connectors',              icon: Plug2 },
    { id: 'reports',         label: 'Reports & Audit',        icon: FileSpreadsheet },
    { id: 'mcf-migration',   label: 'ManifoldCF Migration',   icon: ArrowRightLeft },
    { id: 'logs',            label: 'Real-time Logs',         icon: Activity },
    { id: 'narrativization', label: 'Narrativization',        icon: Wand2 },
    { id: 'settings',        label: 'Settings',                icon: SettingsIcon },
  ] as { id: View; label: string; icon: typeof LayoutDashboard }[]).filter(item => canOpen(item.id, me))

  return (
    <div className="flex h-screen bg-background text-foreground overflow-hidden">
      {/* Sidebar */}
      <aside className={`
        ${isSidebarOpen ? 'w-64' : 'w-20'} 
        transition-all duration-300 ease-in-out
        bg-card border-r border-border flex flex-col
      `}>
        <div className="p-6 flex items-center gap-3">
          <div className="bg-primary p-2 rounded-lg">
            <Search className="w-6 h-6 text-primary-foreground" />
          </div>
          {isSidebarOpen && (
            <span className="font-bold text-lg tracking-tight whitespace-nowrap">
              OpenCrawling
            </span>
          )}
        </div>

        <nav className="flex-1 px-4 py-6 space-y-2">
          {menuItems.map((item) => (
            <button
              key={item.id}
              onClick={() => setActiveView(item.id)}
              className={`
                w-full flex items-center gap-3 px-3 py-2 rounded-md transition-colors
                ${activeView === item.id 
                  ? 'bg-primary/10 text-primary border border-primary/20' 
                  : 'text-muted hover:bg-secondary hover:text-foreground'}
              `}
            >
              <item.icon className="w-5 h-5 min-w-[20px]" />
              {isSidebarOpen && <span className="font-medium">{item.label}</span>}
            </button>
          ))}
        </nav>

        <div className="p-4 border-t border-border">
          <button 
            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
            className="w-full flex items-center justify-center p-2 rounded-md hover:bg-secondary"
          >
            {isSidebarOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 flex flex-col overflow-hidden">
        <header className="h-16 border-b border-border flex items-center justify-between px-8 bg-card/50 backdrop-blur-sm">
          <div className="flex items-center gap-2 text-muted text-sm">
            <span>Admin</span>
            <ChevronRight className="w-4 h-4" />
            <span className="text-foreground font-medium capitalize">
              {activeView === 'mcf-migration' ? 'ManifoldCF Migration' : activeView === 'reports' ? 'Reports & Audit Ledger' : activeView}
            </span>
          </div>
          
          <div className="flex items-center gap-4">
            <div className={`flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium border ${
              systemStatus.system === 'HEALTHY'
                ? 'bg-green-500/10 text-green-500 border-green-500/20'
                : 'bg-red-500/10 text-red-500 border-red-500/20'
            }`}>
              <div className={`w-2 h-2 rounded-full animate-pulse ${
                systemStatus.system === 'HEALTHY' ? 'bg-green-500' : 'bg-red-500'
              }`} />
              System: {systemStatus.system}
            </div>
            {status === 'signed-in' && (
              <div className="flex items-center gap-2">
                <div
                  className="w-8 h-8 rounded-full bg-secondary border border-border flex items-center justify-center"
                  title={[me.principal, me.admin ? 'admin' : null, ...me.groups].filter(Boolean).join(' · ')}
                >
                  <span className="text-xs font-bold">{initials(me.principal)}</span>
                </div>
                <span className="hidden md:inline text-sm text-muted">{me.principal}</span>
                <button onClick={signOut} className="p-1.5 rounded-md hover:bg-secondary" title="Sign out">
                  <LogOut className="w-4 h-4" />
                </button>
              </div>
            )}
          </div>
        </header>

        <div className="flex-1 overflow-y-auto overflow-x-hidden p-4 sm:p-8 custom-scrollbar">
          {activeView === 'dashboard' && <Dashboard />}
          {activeView === 'jobs' && <JobTable setActiveView={setActiveView} />}
          {activeView === 'connectors' && canOpen('connectors', me) && <ConnectorForm />}
          {activeView === 'reports' && <ReportDashboard />}
          {activeView === 'mcf-migration' && canOpen('mcf-migration', me) && <McfMigration />}
          {activeView === 'settings' && canOpen('settings', me) && <Settings />}
          {activeView === 'narrativization' && canOpen('narrativization', me) && <Narrativization />}
          {activeView === 'logs' && canOpen('logs', me) && (
            <div className="flex flex-col gap-4 h-[calc(100vh-12rem)]">
               <h1 className="text-2xl font-bold">Real-time Activity Logs</h1>
               <div className="flex-1 bg-slate-900 rounded-lg p-4 font-mono text-sm overflow-y-auto border border-border text-slate-300 flex flex-col gap-1.5 custom-scrollbar">
                  {logs.length === 0 ? (
                    <p className="text-slate-500 italic">No log entries available.</p>
                  ) : (
                    logs.map((log, index) => (
                      <p key={index} className={getLogLineColor(log)}>
                        {log}
                      </p>
                    ))
                  )}
                  <div ref={logsEndRef} />
               </div>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
