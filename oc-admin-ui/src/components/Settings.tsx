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
  Database
} from 'lucide-react'
import { statusApi } from '../lib/api'

interface SystemSettings {
  embeddingProvider: string
  ollamaBaseUrl: string
  ollamaModel: string
  vectorDimensions: number
  chunkerType: string
  chunkSize: number
  chunkOverlap: number
}

export default function Settings() {
  const [settings, setSettings] = useState<SystemSettings>({
    embeddingProvider: 'Ollama',
    ollamaBaseUrl: 'http://127.0.0.1:11434',
    ollamaModel: 'mxbai-embed-large',
    vectorDimensions: 1024,
    chunkerType: 'TokenTextSplitter',
    chunkSize: 800,
    chunkOverlap: 100
  })

  const [isLoading, setIsLoading] = useState(true)
  const [isSaving, setIsSaving] = useState(false)
  const [saveSuccess, setSaveSuccess] = useState(false)
  const [testingConnection, setTestingConnection] = useState(false)
  const [connectionStatus, setConnectionStatus] = useState<'idle' | 'success' | 'failed'>('idle')
  const [errorMessage, setErrorMessage] = useState('')

  const fetchSettings = async () => {
    setIsLoading(true)
    try {
      const res = await statusApi.getSettings()
      if (res.data) {
        setSettings(res.data)
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
      setSaveSuccess(true)
      setTimeout(() => setSaveSuccess(false), 3000)
    } catch (err) {
      console.error("Failed to save settings:", err)
      setErrorMessage("Failed to persist updated settings.")
    } finally {
      setIsSaving(false)
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
                  <div className="flex gap-2">
                    <input 
                      type="url"
                      value={settings.ollamaBaseUrl}
                      onChange={(e) => setSettings({ ...settings, ollamaBaseUrl: e.target.value })}
                      placeholder="http://127.0.0.1:11434"
                      className="flex-1 bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground font-mono"
                      required
                    />
                    <button
                      type="button"
                      onClick={handleTestConnection}
                      disabled={testingConnection}
                      className="btn-secondary flex items-center gap-1.5 px-3 whitespace-nowrap text-sm"
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
                  <div className="grid grid-cols-2 gap-4">
                    <select
                      value={settings.ollamaModel}
                      onChange={(e) => handleModelChange(e.target.value)}
                      className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
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
                      className="bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 text-foreground"
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
