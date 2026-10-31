import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout, RequireLogin } from './components/Layout';
import { BookingDetailPage } from './pages/BookingDetailPage';
import { BookingsPage } from './pages/BookingsPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { CouponsPage } from './pages/CouponsPage';
import { LoginPage } from './pages/LoginPage';
import { PerformanceDetailPage } from './pages/PerformanceDetailPage';
import { PerformancesPage } from './pages/PerformancesPage';
import { QueuePage } from './pages/QueuePage';
import { SeatsPage } from './pages/SeatsPage';
import { SignupPage } from './pages/SignupPage';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<PerformancesPage />} />
        <Route path="/performances/:performanceId" element={<PerformanceDetailPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route element={<RequireLogin />}>
          <Route path="/performances/:performanceId/queue" element={<QueuePage />} />
          <Route path="/performances/:performanceId/schedules/:scheduleId/seats" element={<SeatsPage />} />
          <Route path="/reservations/:reservationId/checkout" element={<CheckoutPage />} />
          <Route path="/reservations/:reservationId" element={<BookingDetailPage />} />
          <Route path="/reservations" element={<BookingsPage />} />
          <Route path="/coupons" element={<CouponsPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
