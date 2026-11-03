import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout, RequireLogin, RequireRole } from './components/Layout';
import { AdminCouponPage } from './pages/admin/AdminCouponPage';
import { AdminCouponsPage } from './pages/admin/AdminCouponsPage';
import { AdminSchedulesPage } from './pages/admin/AdminSchedulesPage';
import { AdminVenuePage } from './pages/admin/AdminVenuePage';
import { AdminVenuesPage } from './pages/admin/AdminVenuesPage';
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
        <Route path="/signup" element={<SignupPage key="user" />} />
        <Route path="/seller/signup" element={<SignupPage key="seller" seller />} />
        <Route element={<RequireLogin />}>
          <Route element={<RequireRole roles={['USER']} />}>
            <Route path="/performances/:performanceId/queue" element={<QueuePage />} />
            <Route path="/performances/:performanceId/schedules/:scheduleId/seats" element={<SeatsPage />} />
            <Route path="/reservations/:reservationId/checkout" element={<CheckoutPage />} />
            <Route path="/reservations/:reservationId" element={<BookingDetailPage />} />
            <Route path="/reservations" element={<BookingsPage />} />
            <Route path="/coupons" element={<CouponsPage />} />
          </Route>
          <Route element={<RequireRole roles={['MASTER']} />}>
            <Route path="/admin/venues" element={<AdminVenuesPage />} />
            <Route path="/admin/venues/:venueId" element={<AdminVenuePage />} />
            <Route path="/admin/schedules" element={<AdminSchedulesPage />} />
            <Route path="/admin/coupons" element={<AdminCouponsPage />} />
            <Route path="/admin/coupons/:couponId" element={<AdminCouponPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
