import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { AuthPage } from './pages/AuthPage';
import { HomePage } from './pages/HomePage';
import { ResetPasswordPage } from './pages/ResetPasswordPage';
import { SettingsPage } from './pages/SettingsPage';

/** Top-level routing + auth gating. Shows a brief splash while the session is being restored. */
export function App() {
  const { status } = useAuth();

  if (status === 'loading') {
    return (
      <div className="grid h-full place-items-center bg-canvas">
        <div className="size-8 animate-spin rounded-full border-2 border-border-strong border-t-accent" />
      </div>
    );
  }

  const authed = status === 'authenticated';

  return (
    <Routes>
      <Route path="/login" element={authed ? <Navigate to="/" replace /> : <AuthPage />} />
      {/* Reachable signed in or out: the emailed token authorizes the reset, not the session. */}
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/" element={authed ? <HomePage /> : <Navigate to="/login" replace />} />
      <Route
        path="/settings"
        element={authed ? <SettingsPage /> : <Navigate to="/login" replace />}
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
