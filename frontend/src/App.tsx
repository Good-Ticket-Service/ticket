import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminLayout, Layout, RequireLogin, RequireRole, SellerLayout } from './components/Layout';
import { AdminCouponPage } from './pages/admin/AdminCouponPage';
import { AdminCouponCreatePage } from './pages/admin/AdminCouponCreatePage';
import { AdminCouponsPage } from './pages/admin/AdminCouponsPage';
import { AdminPayoutPage } from './pages/admin/AdminPayoutPage';
import { AdminPayoutsPage } from './pages/admin/AdminPayoutsPage';
import { AdminRefundPage } from './pages/admin/AdminRefundPage';
import { AdminRefundsPage } from './pages/admin/AdminRefundsPage';
import { AdminSchedulesPage } from './pages/admin/AdminSchedulesPage';
import { AdminVenuePage } from './pages/admin/AdminVenuePage';
import { AdminVenuesPage } from './pages/admin/AdminVenuesPage';
import { AdminWaitingRoomsPage } from './pages/admin/AdminWaitingRoomsPage';
import { BookingDetailPage } from './pages/BookingDetailPage';
import { BookingsPage } from './pages/BookingsPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { CouponsPage } from './pages/CouponsPage';
import { LoginPage } from './pages/LoginPage';
import { MyPage } from './pages/MyPage';
import { PerformanceDetailPage } from './pages/PerformanceDetailPage';
import { PerformancesPage } from './pages/PerformancesPage';
import { QueuePage } from './pages/QueuePage';
import { SeatsPage } from './pages/SeatsPage';
import { SellerHomePage } from './pages/seller/SellerHomePage';
import { SellerPerformancePage } from './pages/seller/SellerPerformancePage';
import { SellerSchedulePage } from './pages/seller/SellerSchedulePage';
import { SellerPayoutAccountPage, SellerSettlementsPage } from './pages/seller/SellerSettlementsPage';
import { SellerSchedulesPage } from './pages/seller/SellerSchedulesPage';
import { SellerStatementPage } from './pages/seller/SellerStatementPage';
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
          <Route path="/me" element={<MyPage />} />
          <Route element={<RequireRole roles={['SELLER']} />}>
            <Route element={<SellerLayout />}>
              <Route path="/seller" element={<SellerHomePage />} />
              <Route path="/seller/performances/new" element={<SellerPerformancePage key="new" />} />
              <Route path="/seller/performances/:performanceId" element={<SellerPerformancePage />} />
              <Route path="/seller/schedules" element={<SellerSchedulesPage />} />
              <Route path="/seller/schedules/:scheduleId" element={<SellerSchedulePage />} />
              <Route path="/seller/payout-account" element={<SellerPayoutAccountPage />} />
              <Route path="/seller/settlements" element={<SellerSettlementsPage />} />
              <Route path="/seller/settlements/:statementId" element={<SellerStatementPage />} />
            </Route>
          </Route>
          <Route element={<RequireRole roles={['MASTER']} />}>
            <Route element={<AdminLayout />}>
              <Route path="/admin" element={<Navigate to="/admin/venues" replace />} />
              <Route path="/admin/venues" element={<AdminVenuesPage />} />
              <Route path="/admin/venues/:venueId" element={<AdminVenuePage />} />
              <Route path="/admin/schedules" element={<AdminSchedulesPage />} />
              <Route path="/admin/waiting-rooms" element={<AdminWaitingRoomsPage />} />
              <Route path="/admin/coupons" element={<AdminCouponsPage />} />
              <Route path="/admin/coupons/new" element={<AdminCouponCreatePage />} />
              <Route path="/admin/coupons/:couponId" element={<AdminCouponPage />} />
              <Route path="/admin/refunds" element={<AdminRefundsPage />} />
              <Route path="/admin/refunds/:orderId" element={<AdminRefundPage />} />
              <Route path="/admin/payouts" element={<AdminPayoutsPage />} />
              <Route path="/admin/payouts/:payoutId" element={<AdminPayoutPage />} />
            </Route>
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
