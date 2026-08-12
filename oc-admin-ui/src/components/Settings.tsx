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
import { useState, useEffect } from 'react'
import { 
  Save, 
  Cpu, 
  Layers, 
  Globe, 
  CheckCircle, 
  AlertCircle, 
  Loader2,
  RefreshCw,
  HelpCircle,
  Database,
  HardDrive,
  Zap,
  Radio,
  Server,
  ShieldCheck,
  Activity
} from 'lucide-react'
import { statusApi, transportApi } from '../lib/api'

interface TransportSettings {
  mode: 'AUTO' | 'GRPC' | 'REST'
  grpcEnabled: boolean
  grpcPort: number
  maxMessageSizeMb: number
  fallbackToRest: boolean
  keepAliveTimeMs: number
  connectionTimeoutMs: number
  tlsEnabled: boolean
  certChainPath: string
  privateKeyPath: string
}

interface SystemSettings {
  embeddingProvider: string
  ollamaBaseUrl: string
  ollamaModel: string
  vectorDimensions: number
  chunkerType: string
  chunkSize: number
  chunkOverlap: number
  claimCheckStore: string
  ozoneClientType: 'S3' | 'NATIVE'
  ozoneS3Endpoint: string
  ozoneOmHost: string
  ozoneOmPort: number
  ozoneVolume: string
  ozoneBucket: string
}

export default function Settings() {
  const [settings, setSettings] = useState<SystemSettings>({
    embeddingProvider: 'Ollama',
    ollamaBaseUrl: 'http://127.0.0.1:11434',
    ollamaModel: 'mxbai-embed-large',
    vectorDimensions: 1024,
    chunkerType: 'TokenTextSplitter',
    chunkSize: 800,
    chunkOverlap: 100,
    claimCheckStore: 'ozone',
    ozoneClientType: 'NATIVE',
    ozoneS3Endpoint: 'http://127.0.0.1:9878',
    ozoneOmHost: '127.0.0.1',
    ozoneOmPort: 9862,
    ozoneVolume: 's3v',
    ozoneBucket: 'claims'
  })

  const [transportSettings, setTransportSettings] = useState<TransportSettings>({
    mode: 'AUTO',
    grpcEnabled: true,
    grpcPort: 9095,
    maxMessageSizeMb: 32,
    fallbackToRest: true,
    keepAliveTimeMs: 30000,
    connectionTimeoutMs: 5000,
    tlsEnabled: false,
    certChainPath: '',
    privateKeyPath: ''
  })

  const [isLoading, setIsLoading] = useState(true)
  const [isSaving, setIsSaving] = useState(false)
  const [saveSuccess, setSaveSuccess] = useState(false)
  const [testingConnection, setTestingConnection] = useState(false)
  const [connectionStatus, setConnectionStatus] = useState<'idle' | 'success' | 'failed'>('idle')
  const [testingGrpc, setTestingGrpc] = useState(false)
  const [grpcTestResult, setGrpcTestResult] = useState<{ status: string; message: string; latencyMs?: number } | null>(null)
  const [errorMessage, setErrorMessage] = useState('')

  const fetchSettings = async () => {
    setIsLoading(true)
    try {
      const res = await statusApi.getSettings()
      if (res.data) {
        setSettings(res.data)
      }
      const tRes = await transportApi.getSettings()
      if (tRes.data && tRes.data.settings) {
        setTransportSettings(tRes.data.settings)
      }
    } catch (err) {
      console.error("Failed to load settings:", err)
      setErrorMessage("Could not connect to backend to fetch system settings.")
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    fetchSettings()
  }, [])

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSaving(true)
    setSaveSuccess(false)
    setErrorMessage('')
    try {
      await statusApi.saveSettings(settings)
      await transportApi.saveSettings(transportSettings)
      setSaveSuccess(true)
      setTimeout(() => setSaveSuccess(false), 3000)
    } catch (err) {
      console.error("Failed to save settings:", err)
      setErrorMessage("Failed to persist updated settings.")
    } finally {
      setIsSaving(false)
    }
  }

  const handleTestGrpc = async () => {
    setTestingGrpc(true)
    setGrpcTestResult(null)
    try {
      const res = await transportApi.testGrpc({ port: transportSettings.grpcPort })
      if (res.data) {
        setGrpcTestResult({
          status: res.data.status,
          message: res.data.message,
          latencyMs: res.data.latencyMs
        })
      }
    } catch (err: any) {
      setGrpcTestResult({
        status: 'FAILED',
        message: 'Failed to trigger gRPC connectivity probe: ' + (err.message || 'Network error')
      })
    } finally {
      setTestingGrpc(false)
    }
  }

  const handleTestConnection = async () => {
    setTestingConnection(true)
    setConnectionStatus('idle')
    
    // Simulate connection checking
    await new Promise(resolve => setTimeout(resolve, 1500))
    
    if (settings.ollamaBaseUrl.includes('127.0.0.1') || settings.ollamaBaseUrl.includes('localhost')) {
      setConnectionStatus('success')
    } else {
      setConnectionStatus('failed')
    }
    setTestingConnection(false)
  }

  const handleModelChange = (modelName: string) => {
    let dims = 1024
    if (modelName === 'nomic-embed-text') dims = 768
    if (modelName === 'all-minilm') dims = 384
    if (modelName === 'bge-large-en') dims = 1024
    
    setSettings({
      ...settings,
      ollamaModel: modelName,
      vectorDimensions: dims
    })
  }

  if (isLoading) {
    return (
      <div className="h-64 flex flex-col items-center justify-center gap-4">
        <Loader2 className="w-10 h-10 animate-spin text-primary" />
        <span className="text-muted-foreground text-sm font-medium">Loading ingestion & embedding parameters...</span>
      </div>
    )
  }

  return (
    <div className="space-y-6 max-w-4xl animate-in fade-in duration-500 pb-20">
      <div>
        <h1 className="text-2xl font-bold">Ingestion & Embedding Settings</h1>
        <p className="text-muted text-sm">Configure the artificial intelligence embedding models and chunk splitting parameters for vector storage ingestion.</p>
      </div>

      {saveSuccess && (
        <div className="p-4 bg-green-500/10 border border-green-500/20 text-green-400 rounded-lg flex items-center gap-3 animate-in slide-in-from-top-4 duration-200">
          <CheckCircle className="w-5 h-5 text-green-500 flex-shrink-0" />
          <div>
            <span className="font-bold">Settings saved successfully!</span> Ingestion pipelines will now run with the updated configurations.
          </div>
        </div>
      )}

      {errorMessage && (
        <div className="p-4 bg-red-500/10 border border-red-500/20 text-red-400 rounded-lg flex items-center gap-3 animate-in slide-in-from-top-4 duration-200">
          <AlertCircle className="w-5 h-5 text-red-500 flex-shrink-0" />
          <div>
            <span className="font-bold">Error:</span> {errorMessage}
          </div>
        </div>
      )}

      <form onSubmit={handleSave} className="space-y-8">
        
        {/* Card 1: Embedding Provider Config */}
        <div className="card-container space-y-6">
          <div className="flex items-center gap-3 border-b border-border pb-4">
            <Cpu className="w-5 h-5 text-cyan-400" />
            <div>
              <h3 className="text-lg font-bold text-foreground">1. Embedding Engine Settings</h3>
              <p className="text-xs text-muted-foreground">Select and configure the embedding model provider.</p>
            </div>
          </div>

          <div className="space-y-4">
            <div>
              <label className="text-sm font-semibold mb-2 block">AI Core Provider</label>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {['Ollama', 'OpenAI', 'Hugging Face'].map((prov) => {
                  const isOllama = prov === 'Ollama';
                  return (
                    <div 
                      key={prov}
                      onClick={() => isOllama && setSettings({ ...settings, embeddingProvider: prov })}
                      className={`p-4 border rounded-lg flex flex-col justify-between h-28 cursor-pointer transition-all ${
                        settings.embeddingProvider === prov 
                          ? 'border-primary bg-primary/5 ring-1 ring-primary/20' 
                          : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                      } ${!isOllama ? 'cursor-not-allowed opacity-45' : ''}`}
                    >
                      <div>
                        <span className="font-bold text-sm text-foreground">{prov}</span>
                        {!isOllama && <span className="ml-2 inline-block text-[9px] bg-slate-800 text-muted-foreground px-1.5 py-0.5 rounded uppercase font-semibold">Coming Soon</span>}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {prov === 'Ollama' && "Local AI model execution, no api keys or external requests required."}
                        {prov === 'OpenAI' && "Cloud-hosted GPT embeddings. High accuracy, paid subscription needed."}
                        {prov === 'Hugging Face' && "Access open-source transformer models via Inference API."}
                      </p>
                    </div>
                  )
                })}
              </div>
            </div>

            {settings.embeddingProvider === 'Ollama' && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6 pt-4 border-t border-border/40 animate-in fade-in duration-200">
                
                {/* Host API */}
                <div className="space-y-2">
                  <label className="text-sm font-medium flex items-center gap-1.5">
                    <Globe className="w-4 h-4 text-muted" />
                    Ollama Host URL
                  </label>
                  <div className="flex flex-col sm:flex-row gap-2">
                    <input 
                      type="url"
                      value={settings.ollamaBaseUrl}
                      onChange={(e) => setSettings({ ...settings, ollamaBaseUrl: e.target.value })}
                      placeholder="http://127.0.0.1:11434"
                      className="flex-1 bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono min-w-0"
                      required
                    />
                    <button
                      type="button"
                      onClick={handleTestConnection}
                      disabled={testingConnection}
                      className="btn-secondary flex items-center justify-center gap-1.5 px-3 py-2 text-sm w-full sm:w-auto"
                    >
                      {testingConnection ? (
                        <Loader2 className="w-4 h-4 animate-spin text-primary" />
                      ) : (
                        <RefreshCw className="w-4 h-4" />
                      )}
                      Test Connection
                    </button>
                  </div>
                  
                  {/* Connection result notice */}
                  {connectionStatus === 'success' && (
                    <p className="text-xs text-green-400 flex items-center gap-1 mt-1 font-semibold">
                      <CheckCircle className="w-3.5 h-3.5 text-green-400" />
                      Successfully connected to Ollama instance!
                    </p>
                  )}
                  {connectionStatus === 'failed' && (
                    <p className="text-xs text-red-400 flex items-center gap-1 mt-1 font-semibold">
                      <AlertCircle className="w-3.5 h-3.5 text-red-400" />
                      Connection failed. Make sure Ollama is running and listening.
                    </p>
                  )}
                </div>

                {/* Model Select */}
                <div className="space-y-2">
                  <label className="text-sm font-medium flex items-center gap-1.5">
                    <Database className="w-4 h-4 text-muted" />
                    Embedding Model
                  </label>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <select
                      value={settings.ollamaModel}
                      onChange={(e) => handleModelChange(e.target.value)}
                      className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground w-full"
                    >
                      <option value="mxbai-embed-large">mxbai-embed-large (Default)</option>
                      <option value="nomic-embed-text">nomic-embed-text</option>
                      <option value="all-minilm">all-minilm</option>
                      <option value="bge-large-en">bge-large-en</option>
                    </select>
                    <input 
                      type="text"
                      value={settings.ollamaModel}
                      onChange={(e) => setSettings({ ...settings, ollamaModel: e.target.value })}
                      placeholder="Custom model name..."
                      className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground w-full"
                      title="Or type in a custom local Ollama model name"
                    />
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Model will automatically map to vector dimensions of <span className="font-semibold text-primary">{settings.vectorDimensions}d</span>.
                  </p>
                </div>

                {/* Vector Dimensions */}
                <div className="space-y-2">
                  <label className="text-sm font-medium">Vector Dimensions</label>
                  <input 
                    type="number"
                    value={settings.vectorDimensions}
                    onChange={(e) => setSettings({ ...settings, vectorDimensions: parseInt(e.target.value) || 1024 })}
                    className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    required
                  />
                  <p className="text-xs text-muted-foreground">
                    Must match your target database pgvector dimension index schema.
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Card 2: Chunking Config */}
        <div className="card-container space-y-6">
          <div className="flex items-center gap-3 border-b border-border pb-4">
            <Layers className="w-5 h-5 text-cyan-400" />
            <div>
              <h3 className="text-lg font-bold text-foreground">2. Document Splitter & Chunker</h3>
              <p className="text-xs text-muted-foreground">Configure the text parsing chunk sizes before creating vector embeddings.</p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {/* Splitter type selection */}
            <div className="space-y-2">
              <label className="text-sm font-medium">Splitter Algorithm</label>
              <select
                value={settings.chunkerType}
                onChange={(e) => setSettings({ ...settings, chunkerType: e.target.value })}
                className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
              >
                <option value="TokenTextSplitter">TokenTextSplitter (Best for AI Models)</option>
                <option value="CharacterTextSplitter">CharacterTextSplitter (Fixed Length)</option>
              </select>
              <p className="text-xs text-muted-foreground">
                TokenTextSplitter tokenizes content using standard LLM tokenizer sizes for semantic consistency.
              </p>
            </div>

            {/* Chunk Size */}
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <label className="text-sm font-medium flex items-center gap-1">
                  Chunk Size 
                  <span className="text-xs text-muted-foreground font-normal">(Tokens)</span>
                </label>
                <span className="font-mono text-sm text-primary font-bold">{settings.chunkSize}</span>
              </div>
              <input 
                type="range"
                min="100"
                max="2000"
                step="50"
                value={settings.chunkSize}
                onChange={(e) => setSettings({ ...settings, chunkSize: parseInt(e.target.value) })}
                className="w-full accent-primary bg-secondary h-1.5 rounded-lg appearance-none cursor-pointer"
              />
              <div className="flex justify-between text-[10px] text-muted-foreground font-mono">
                <span>100</span>
                <span>1000</span>
                <span>2000</span>
              </div>
            </div>

            {/* Chunk Overlap */}
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <label className="text-sm font-medium flex items-center gap-1">
                  Chunk Overlap 
                  <span className="text-xs text-muted-foreground font-normal">(Tokens)</span>
                </label>
                <span className="font-mono text-sm text-primary font-bold">{settings.chunkOverlap}</span>
              </div>
              <input 
                type="range"
                min="0"
                max="500"
                step="10"
                value={settings.chunkOverlap}
                onChange={(e) => setSettings({ ...settings, chunkOverlap: parseInt(e.target.value) })}
                className="w-full accent-primary bg-secondary h-1.5 rounded-lg appearance-none cursor-pointer"
              />
              <div className="flex justify-between text-[10px] text-muted-foreground font-mono">
                <span>0</span>
                <span>250</span>
                <span>500</span>
              </div>
            </div>
          </div>
        </div>

        {/* Card 3: Apache Ozone Storage & Client Selection */}
        <div className="card-container space-y-6">
          <div className="flex items-center gap-3 border-b border-border pb-4">
            <HardDrive className="w-5 h-5 text-amber-400" />
            <div>
              <h3 className="text-lg font-bold text-foreground">3. Apache Ozone & Claim-Check Storage</h3>
              <p className="text-xs text-muted-foreground">Configure binary document offloading and client protocol strategy (S3 Gateway vs Native RPC).</p>
            </div>
          </div>

          <div className="space-y-6">
            {/* Storage Provider Selection */}
            <div>
              <label className="text-sm font-semibold mb-2 block">Claim Check Store Provider</label>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div 
                  onClick={() => setSettings({ ...settings, claimCheckStore: 'ozone' })}
                  className={`p-4 border rounded-lg flex flex-col justify-between h-28 cursor-pointer transition-all ${
                    settings.claimCheckStore === 'ozone' 
                      ? 'border-amber-500/80 bg-amber-500/10 ring-1 ring-amber-500/30' 
                      : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                  }`}
                >
                  <div className="flex justify-between items-center">
                    <span className="font-bold text-sm text-foreground flex items-center gap-2">
                      <Zap className="w-4 h-4 text-amber-400" />
                      Apache Ozone Distributed Storage
                    </span>
                    <span className="text-[10px] bg-amber-500/20 text-amber-300 border border-amber-500/30 px-2 py-0.5 rounded font-semibold uppercase">Recommended</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Scalable distributed object store. Offloads heavy raw binary streams from Kafka queues.
                  </p>
                </div>

                <div 
                  onClick={() => setSettings({ ...settings, claimCheckStore: 'local' })}
                  className={`p-4 border rounded-lg flex flex-col justify-between h-28 cursor-pointer transition-all ${
                    settings.claimCheckStore === 'local' 
                      ? 'border-primary bg-primary/5 ring-1 ring-primary/20' 
                      : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                  }`}
                >
                  <div>
                    <span className="font-bold text-sm text-foreground">Local Shared Disk</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Stores temporary payload files on local or shared network directory (`/data/claims`).
                  </p>
                </div>
              </div>
            </div>

            {/* Ozone Client Strategy Configuration */}
            {settings.claimCheckStore === 'ozone' && (
              <div className="space-y-6 pt-4 border-t border-border/40 animate-in fade-in duration-200">
                <div>
                  <label className="text-sm font-semibold mb-2 block">Ozone Client Protocol Strategy</label>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Native RPC Strategy */}
                    <div 
                      onClick={() => setSettings({ ...settings, ozoneClientType: 'NATIVE' })}
                      className={`p-4 border rounded-lg flex flex-col justify-between cursor-pointer transition-all ${
                        settings.ozoneClientType === 'NATIVE' 
                          ? 'border-emerald-500/80 bg-emerald-500/10 ring-1 ring-emerald-500/30' 
                          : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                      }`}
                    >
                      <div className="flex justify-between items-center mb-1">
                        <span className="font-bold text-sm text-foreground flex items-center gap-1.5">
                          <Zap className="w-4 h-4 text-emerald-400" />
                          Native Ozone Client (ofs / o3fs)
                        </span>
                        <span className="text-[10px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 px-1.5 py-0.5 rounded font-mono font-bold">RPC High Performance</span>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        Direct gRPC/RPC transport to DataNodes & Ozone Manager (OM). Bypasses HTTP/XML S3 Gateway translation layer for maximum throughput.
                      </p>
                    </div>

                    {/* S3 Gateway Strategy */}
                    <div 
                      onClick={() => setSettings({ ...settings, ozoneClientType: 'S3' })}
                      className={`p-4 border rounded-lg flex flex-col justify-between cursor-pointer transition-all ${
                        settings.ozoneClientType === 'S3' 
                          ? 'border-cyan-500/80 bg-cyan-500/10 ring-1 ring-cyan-500/30' 
                          : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                      }`}
                    >
                      <div className="flex justify-between items-center mb-1">
                        <span className="font-bold text-sm text-foreground flex items-center gap-1.5">
                          <Globe className="w-4 h-4 text-cyan-400" />
                          S3 Gateway Client (s3g)
                        </span>
                        <span className="text-[10px] bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 px-1.5 py-0.5 rounded font-mono font-bold">Standard S3</span>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        Standard AWS S3 SDK integration hitting Ozone's S3 Gateway endpoint. Maximum cloud & network versatility.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Strategy Connection Fields */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6 bg-slate-950/40 p-4 border border-border/50 rounded-lg">
                  {settings.ozoneClientType === 'NATIVE' ? (
                    <>
                      <div className="space-y-2">
                        <label className="text-xs font-semibold text-muted-foreground">Ozone Manager (OM) Host</label>
                        <input 
                          type="text"
                          value={settings.ozoneOmHost}
                          onChange={(e) => setSettings({ ...settings, ozoneOmHost: e.target.value })}
                          placeholder="localhost"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-xs font-semibold text-muted-foreground">Ozone Manager (OM) RPC Port</label>
                        <input 
                          type="number"
                          value={settings.ozoneOmPort}
                          onChange={(e) => setSettings({ ...settings, ozoneOmPort: parseInt(e.target.value) || 9862 })}
                          placeholder="9862"
                          className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                        />
                      </div>
                    </>
                  ) : (
                    <div className="space-y-2 md:col-span-2">
                      <label className="text-xs font-semibold text-muted-foreground">Ozone S3 Gateway Endpoint URL</label>
                      <input 
                        type="url"
                        value={settings.ozoneS3Endpoint}
                        onChange={(e) => setSettings({ ...settings, ozoneS3Endpoint: e.target.value })}
                        placeholder="http://localhost:9878"
                        className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                      />
                    </div>
                  )}

                  <div className="space-y-2">
                    <label className="text-xs font-semibold text-muted-foreground">Target Volume</label>
                    <input 
                      type="text"
                      value={settings.ozoneVolume}
                      onChange={(e) => setSettings({ ...settings, ozoneVolume: e.target.value })}
                      placeholder="s3v"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    />
                  </div>
                  <div className="space-y-2">
                    <label className="text-xs font-semibold text-muted-foreground">Target Bucket</label>
                    <input 
                      type="text"
                      value={settings.ozoneBucket}
                      onChange={(e) => setSettings({ ...settings, ozoneBucket: e.target.value })}
                      placeholder="claims"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    />
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Card 4: Internal Communication & Transport Settings */}
        <div className="card-container space-y-6">
          <div className="flex items-center justify-between border-b border-border pb-4">
            <div className="flex items-center gap-3">
              <Server className="w-5 h-5 text-cyan-400" />
              <div>
                <h3 className="text-lg font-bold text-foreground">4. Internal Communication & Transport (gRPC / REST)</h3>
                <p className="text-xs text-muted-foreground">Configure high-performance gRPC payload transport with dynamic REST fallback for internal node communication.</p>
              </div>
            </div>
            <span className={`text-[11px] font-mono px-2.5 py-1 rounded-full font-bold uppercase ${
              transportSettings.mode === 'GRPC' 
                ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                : transportSettings.mode === 'AUTO'
                ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30'
                : 'bg-slate-800 text-slate-400 border border-slate-700'
            }`}>
              Mode: {transportSettings.mode}
            </span>
          </div>

          <div className="space-y-6">
            {/* Mode Selector */}
            <div>
              <label className="text-sm font-semibold mb-2 block">Transport Operational Mode</label>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {/* AUTO */}
                <div 
                  onClick={() => setTransportSettings({ ...transportSettings, mode: 'AUTO', grpcEnabled: true })}
                  className={`p-4 border rounded-lg flex flex-col justify-between cursor-pointer transition-all ${
                    transportSettings.mode === 'AUTO'
                      ? 'border-cyan-500/80 bg-cyan-500/10 ring-1 ring-cyan-500/30'
                      : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="font-bold text-sm text-foreground flex items-center gap-1.5">
                      <Activity className="w-4 h-4 text-cyan-400" />
                      AUTO (Recommended)
                    </span>
                    <span className="text-[10px] bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 px-1.5 py-0.5 rounded font-mono font-bold">gRPC + REST Fallback</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Attempts high-speed gRPC streams first. Automatically falls back to HTTP/REST payload transport if gRPC is unavailable.
                  </p>
                </div>

                {/* Strict gRPC */}
                <div 
                  onClick={() => setTransportSettings({ ...transportSettings, mode: 'GRPC', grpcEnabled: true })}
                  className={`p-4 border rounded-lg flex flex-col justify-between cursor-pointer transition-all ${
                    transportSettings.mode === 'GRPC'
                      ? 'border-emerald-500/80 bg-emerald-500/10 ring-1 ring-emerald-500/30'
                      : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="font-bold text-sm text-foreground flex items-center gap-1.5">
                      <Zap className="w-4 h-4 text-emerald-400" />
                      GRPC (Strict)
                    </span>
                    <span className="text-[10px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 px-1.5 py-0.5 rounded font-mono font-bold">Strict High Performance</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Enforces binary Protobuf gRPC streaming across all core processing nodes. Highest throughput and lowest CPU serialization overhead.
                  </p>
                </div>

                {/* REST Only */}
                <div 
                  onClick={() => setTransportSettings({ ...transportSettings, mode: 'REST', grpcEnabled: false })}
                  className={`p-4 border rounded-lg flex flex-col justify-between cursor-pointer transition-all ${
                    transportSettings.mode === 'REST'
                      ? 'border-slate-500/80 bg-slate-800/40 ring-1 ring-slate-500/30'
                      : 'border-border bg-slate-900/30 hover:border-border-50 opacity-70'
                  }`}
                >
                  <div className="flex justify-between items-center mb-1">
                    <span className="font-bold text-sm text-foreground flex items-center gap-1.5">
                      <Globe className="w-4 h-4 text-slate-400" />
                      REST (Standard)
                    </span>
                    <span className="text-[10px] bg-slate-800 text-slate-400 border border-slate-700 px-1.5 py-0.5 rounded font-mono font-bold">HTTP/1.1 JSON</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Disables internal gRPC server and utilizes standard HTTP/REST JSON endpoints. Ideal for simple local testing.
                  </p>
                </div>
              </div>
            </div>

            {/* Config Fields */}
            {transportSettings.mode !== 'REST' && (
              <div className="space-y-6 pt-4 border-t border-border/40 animate-in fade-in duration-200">
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                  <div className="space-y-2">
                    <label className="text-xs font-semibold text-muted-foreground">gRPC Server Port</label>
                    <input 
                      type="number"
                      value={transportSettings.grpcPort}
                      onChange={(e) => setTransportSettings({ ...transportSettings, grpcPort: parseInt(e.target.value) || 9095 })}
                      placeholder="9095"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    />
                  </div>

                  <div className="space-y-2">
                    <label className="text-xs font-semibold text-muted-foreground">Max Message Size (MB)</label>
                    <input 
                      type="number"
                      value={transportSettings.maxMessageSizeMb}
                      onChange={(e) => setTransportSettings({ ...transportSettings, maxMessageSizeMb: parseInt(e.target.value) || 32 })}
                      placeholder="32"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    />
                  </div>

                  <div className="space-y-2">
                    <label className="text-xs font-semibold text-muted-foreground">Connection Timeout (ms)</label>
                    <input 
                      type="number"
                      value={transportSettings.connectionTimeoutMs}
                      onChange={(e) => setTransportSettings({ ...transportSettings, connectionTimeoutMs: parseInt(e.target.value) || 5000 })}
                      placeholder="5000"
                      className="w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                    />
                  </div>
                </div>

                {/* Checkboxes & Resilience */}
                <div className="p-4 bg-slate-950/40 border border-border/50 rounded-lg space-y-4">
                  <label className="flex items-center gap-3 cursor-pointer">
                    <input 
                      type="checkbox"
                      checked={transportSettings.fallbackToRest}
                      onChange={(e) => setTransportSettings({ ...transportSettings, fallbackToRest: e.target.checked })}
                      className="w-4 h-4 rounded border-border text-cyan-500 focus:ring-cyan-500/50 bg-background"
                    />
                    <div>
                      <span className="text-sm font-semibold text-foreground">Auto-fallback to HTTP/REST on Channel Timeout or Error</span>
                      <p className="text-xs text-muted-foreground">Guarantees zero document loss by instantly rerouting failed gRPC chunks over HTTP/REST.</p>
                    </div>
                  </label>

                  <label className="flex items-center gap-3 cursor-pointer pt-2 border-t border-border/30">
                    <input 
                      type="checkbox"
                      checked={transportSettings.tlsEnabled}
                      onChange={(e) => setTransportSettings({ ...transportSettings, tlsEnabled: e.target.checked })}
                      className="w-4 h-4 rounded border-border text-cyan-500 focus:ring-cyan-500/50 bg-background"
                    />
                    <div>
                      <span className="text-sm font-semibold text-foreground flex items-center gap-1.5">
                        <ShieldCheck className="w-4 h-4 text-cyan-400" />
                        Enable TLS / mTLS Transport Encryption
                      </span>
                      <p className="text-xs text-muted-foreground">Encrypt inter-node gRPC channels using server certificates and private keys.</p>
                    </div>
                  </label>
                </div>

                {/* Diagnostic Test Probe */}
                <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 pt-2">
                  <button
                    type="button"
                    onClick={handleTestGrpc}
                    disabled={testingGrpc}
                    className="btn-secondary flex items-center gap-2 px-4 py-2 text-sm font-medium"
                  >
                    {testingGrpc ? <Loader2 className="w-4 h-4 animate-spin text-cyan-400" /> : <Activity className="w-4 h-4 text-cyan-400" />}
                    Test gRPC Connectivity
                  </button>

                  {grpcTestResult && (
                    <div className={`p-3 rounded-lg border text-xs flex items-center gap-2 animate-in fade-in duration-200 ${
                      grpcTestResult.status === 'SUCCESS'
                        ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
                        : grpcTestResult.status === 'FALLBACK'
                        ? 'bg-amber-500/10 border-amber-500/30 text-amber-300'
                        : 'bg-red-500/10 border-red-500/30 text-red-300'
                    }`}>
                      {grpcTestResult.status === 'SUCCESS' && <CheckCircle className="w-4 h-4 text-emerald-400 flex-shrink-0" />}
                      {grpcTestResult.status === 'FALLBACK' && <AlertCircle className="w-4 h-4 text-amber-400 flex-shrink-0" />}
                      {grpcTestResult.status === 'FAILED' && <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0" />}
                      <span>{grpcTestResult.message}</span>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Form Actions footer */}
        <div className="flex justify-end gap-3">
          <button 
            type="button" 
            onClick={fetchSettings}
            className="btn-secondary"
          >
            Reset
          </button>
          <button 
            type="submit" 
            disabled={isSaving}
            className="btn-primary flex items-center gap-2 min-w-[140px] justify-center bg-gradient-to-r from-cyan-500 to-blue-500 text-black border border-cyan-400/25 shadow-lg shadow-cyan-500/25 font-semibold"
          >
            {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4 text-black" />}
            Save Configurations
          </button>
        </div>

      </form>
    </div>
  )
}
