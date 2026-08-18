import React, { useState, useEffect } from 'react';
import { getTimesheets, approveTimesheet, rejectTimesheet, submitTimesheet } from '../../api/timesheetApi';
import { DataTable } from '../../components/common/DataTable';
import { StatusBadge } from '../../components/common/StatusBadge';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { Button } from '../../components/common/Button';
import { Modal } from '../../components/common/Modal';
import { FormInput } from '../../components/common/FormInput';
import { 
  Plus, 
  Eye, 
  CheckCircle, 
  XCircle, 
  Send, 
  Clock, 
  User, 
  Briefcase, 
  Calendar, 
  DollarSign, 
  AlertTriangle, 
  AlertCircle,
  RefreshCw,
  FileText
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { TimesheetWizardModal } from '../../components/timesheets/TimesheetWizardModal';

export const TimesheetsList = () => {
  const [timesheets, setTimesheets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const { role } = useAuth();

  // Wizard and Modals state
  const [isWizardOpen, setIsWizardOpen] = useState(false);
  const [isViewModalOpen, setIsViewModalOpen] = useState(false);
  const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
  const [selectedTimesheet, setSelectedTimesheet] = useState(null);
  const [rejectReason, setRejectReason] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [feedback, setFeedback] = useState({ show: false, message: '', type: 'success' });

  const fetchTimesheetsList = async (isManual = false) => {
    if (isManual) setRefreshing(true);
    else setLoading(true);
    try {
      const data = await getTimesheets();
      const list = Array.isArray(data) ? data : (data?.content || data?.data?.content || data?.data || []);
      setTimesheets(list);
    } catch (error) {
      console.error("Failed to fetch timesheets", error);
      setTimesheets([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchTimesheetsList();
  }, []);

  const showNotification = (message, type = 'success') => {
    setFeedback({ show: true, message, type });
    setTimeout(() => setFeedback({ show: false, message: '', type: 'success' }), 4000);
  };

  const handleApprove = async (ts) => {
    setActionLoading(true);
    try {
      await approveTimesheet(ts.id);
      showNotification(`Timesheet for ${ts.contractor?.user?.name || ts.contractorName || 'Contractor'} on ${ts.workDate || 'selected date'} approved successfully!`);
      if (isViewModalOpen) setIsViewModalOpen(false);
      fetchTimesheetsList(true);
    } catch (err) {
      showNotification(err.response?.data?.message || 'Failed to approve timesheet', 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const openRejectModal = (ts) => {
    setSelectedTimesheet(ts);
    setRejectReason('');
    setIsRejectModalOpen(true);
  };

  const handleReject = async () => {
    if (!rejectReason.trim() || !selectedTimesheet) return;
    setActionLoading(true);
    try {
      await rejectTimesheet(selectedTimesheet.id, rejectReason);
      showNotification(`Timesheet rejected. Reason recorded and returned for revision.`);
      setIsRejectModalOpen(false);
      if (isViewModalOpen) setIsViewModalOpen(false);
      fetchTimesheetsList(true);
    } catch (err) {
      showNotification(err.response?.data?.message || 'Failed to reject timesheet', 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const handleSubmit = async (ts) => {
    setActionLoading(true);
    try {
      await submitTimesheet(ts.id);
      showNotification(`Timesheet submitted for manager review!`);
      fetchTimesheetsList(true);
    } catch (err) {
      showNotification(err.response?.data?.message || 'Failed to submit timesheet', 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const openViewModal = (ts) => {
    setSelectedTimesheet(ts);
    setIsViewModalOpen(true);
  };

  // Filter and search logic
  const filteredTimesheets = timesheets.filter(ts => {
    const s = (ts.status || '').toUpperCase();
    if (statusFilter === 'PENDING' && !(s === 'SUBMITTED' || s === 'REVIEW REQUIRED' || s === 'UNDER_REVIEW')) {
      return false;
    }
    if (statusFilter === 'APPROVED' && s !== 'APPROVED') return false;
    if (statusFilter === 'REJECTED' && s !== 'REJECTED') return false;
    if (statusFilter === 'DRAFT' && s !== 'DRAFT') return false;

    if (searchTerm.trim()) {
      const q = searchTerm.toLowerCase().trim();
      const cName = (ts.contractor?.user?.name || ts.contractorName || '').toLowerCase();
      const pName = (ts.projectName || ts.project?.projectName || '').toLowerCase();
      const desc = (ts.description || '').toLowerCase();
      const date = (ts.workDate || '').toLowerCase();
      return cName.includes(q) || pName.includes(q) || desc.includes(q) || date.includes(q);
    }

    return true;
  });

  const columns = [
    { 
      header: 'Reference ID', 
      cell: (row) => (
        <span className="font-mono text-xs font-semibold text-slate-900 dark:text-white">
          TS-{String(row.id || '').substring(0, 8).toUpperCase()}
        </span>
      )
    },
    { 
      header: 'Contractor', 
      cell: (row) => (
        <div>
          <p className="font-semibold text-slate-900 dark:text-white text-xs">
            {row.contractor?.user?.name || row.contractorName || row.contractor?.name || 'Contractor'}
          </p>
          <span className="text-[11px] text-slate-500">
            {row.contractor?.jobRole || 'External Talent'}
          </span>
        </div>
      )
    },
    { 
      header: 'Project', 
      cell: (row) => (
        <span className="text-slate-700 dark:text-slate-300 text-xs font-medium">
          {row.projectName || row.project?.projectName || 'Enterprise Delivery'}
        </span>
      )
    },
    { 
      header: 'Work Date', 
      cell: (row) => (
        <span className="text-xs text-slate-600 dark:text-slate-400">
          {row.workDate || row.date || row.submittedDate || '-'}
        </span>
      )
    },
    { 
      header: 'Hours', 
      cell: (row) => (
        <span className="font-bold text-slate-900 dark:text-white text-xs">
          {row.totalHours ?? 0} hrs
        </span>
      )
    },
    { 
      header: 'Status', 
      cell: (row) => <StatusBadge status={row.status || 'Draft'} /> 
    },
    {
      header: 'Risk Score',
      cell: (row) => {
        let reasons = '';
        if (row.riskReasons) {
          try {
            const parsed = JSON.parse(row.riskReasons);
            if (Array.isArray(parsed)) {
              reasons = parsed.map(r => r.message).join(' | ');
            }
          } catch (e) { }
        }
        const level = (row.riskLevel || 'LOW').toUpperCase();
        return (
          <span 
            title={reasons || 'No rule violations detected'} 
            className={`cursor-help inline-flex items-center px-2 py-0.5 text-[11px] font-semibold rounded-full border ${
              level === 'CRITICAL' ? 'bg-red-50 text-red-700 border-red-200 dark:bg-red-950/40 dark:text-red-300 dark:border-red-800' :
              level === 'HIGH' ? 'bg-orange-50 text-orange-700 border-orange-200 dark:bg-orange-950/40 dark:text-orange-300 dark:border-orange-800' :
              level === 'MEDIUM' ? 'bg-yellow-50 text-yellow-700 border-yellow-200 dark:bg-yellow-950/40 dark:text-yellow-300 dark:border-yellow-800' :
              'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800'
            }`}
          >
            {level} {row.riskScore ? `(${row.riskScore})` : ''}
          </span>
        );
      }
    },
    {
      header: 'Actions',
      cell: (row) => {
        const s = (row.status || '').toUpperCase();
        const isPending = s === 'SUBMITTED' || s === 'REVIEW REQUIRED' || s === 'UNDER_REVIEW';
        const isDraft = s === 'DRAFT';
        const canManage = role === 'VENDOR' || role === 'ADMIN';

        return (
          <div className="flex items-center space-x-1.5">
            <button 
              onClick={() => openViewModal(row)}
              className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-primary-600 dark:hover:bg-slate-800 transition-colors" 
              title="View Timesheet Details"
            >
              <Eye className="h-4 w-4" />
            </button>

            {canManage && isPending && (
              <>
                <button 
                  onClick={() => handleApprove(row)}
                  disabled={actionLoading}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-emerald-600 dark:hover:bg-slate-800 transition-colors" 
                  title="Approve Timesheet"
                >
                  <CheckCircle className="h-4 w-4" />
                </button>
                <button 
                  onClick={() => openRejectModal(row)}
                  disabled={actionLoading}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800 transition-colors" 
                  title="Reject Timesheet"
                >
                  <XCircle className="h-4 w-4" />
                </button>
              </>
            )}

            {isDraft && (role === 'CONTRACTOR' || role === 'ADMIN') && (
              <button 
                onClick={() => handleSubmit(row)}
                disabled={actionLoading}
                className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-blue-600 dark:hover:bg-slate-800 transition-colors" 
                title="Submit for Approval"
              >
                <Send className="h-4 w-4" />
              </button>
            )}
          </div>
        );
      }
    },
  ];

  if (loading) return <LoadingSpinner size="lg" className="mt-20" />;

  return (
    <div className="space-y-6">
      {feedback.show && (
        <div className={`flex items-center gap-2 rounded-lg p-4 text-sm font-medium border ${
          feedback.type === 'success' 
            ? 'bg-emerald-50 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300 border-emerald-200 dark:border-emerald-800' 
            : 'bg-red-50 text-red-800 dark:bg-red-950/40 dark:text-red-300 border-red-200 dark:border-red-800'
        }`}>
          {feedback.type === 'success' ? <CheckCircle className="h-4 w-4" /> : <AlertCircle className="h-4 w-4" />}
          {feedback.message}
        </div>
      )}

      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
            Timesheets Management
          </h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            Track daily work entries, calculate standard/overtime hours, and process manager authorizations.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <Button 
            variant="outline" 
            onClick={() => fetchTimesheetsList(true)} 
            isLoading={refreshing}
            className="flex items-center gap-1.5 text-xs"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </Button>

          {role === 'CONTRACTOR' && (
            <Button className="flex items-center gap-1.5 text-xs" onClick={() => setIsWizardOpen(true)}>
              <Plus className="h-4 w-4" />
              Add Timesheet
            </Button>
          )}
        </div>
      </div>

      {/* Filter Tabs & Search Bar */}
      <div className="rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 overflow-hidden">
        <div className="border-b border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 p-3.5 flex flex-col sm:flex-row gap-3 justify-between items-start sm:items-center">
          <div className="flex flex-wrap gap-1.5 bg-slate-200/70 dark:bg-slate-800 p-1 rounded-lg">
            {[
              { key: 'ALL', label: `All (${timesheets.length})` },
              { key: 'PENDING', label: `Pending Review (${timesheets.filter(t => (t.status || '').toUpperCase() === 'SUBMITTED' || (t.status || '').toUpperCase() === 'REVIEW REQUIRED').length})` },
              { key: 'APPROVED', label: `Approved (${timesheets.filter(t => (t.status || '').toUpperCase() === 'APPROVED').length})` },
              { key: 'REJECTED', label: `Rejected (${timesheets.filter(t => (t.status || '').toUpperCase() === 'REJECTED').length})` },
              { key: 'DRAFT', label: `Drafts (${timesheets.filter(t => (t.status || '').toUpperCase() === 'DRAFT').length})` }
            ].map(tab => (
              <button
                key={tab.key}
                onClick={() => setStatusFilter(tab.key)}
                className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors ${
                  statusFilter === tab.key
                    ? 'bg-white dark:bg-slate-700 text-slate-900 dark:text-white shadow-xs font-semibold'
                    : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <div className="relative w-full max-w-xs">
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search contractor, project, date..."
              className="h-8.5 w-full rounded-lg border border-slate-300 bg-white px-3 text-xs placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </div>
        </div>

        {filteredTimesheets.length === 0 ? (
          <div className="flex flex-col items-center justify-center p-12 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-100 text-slate-400 dark:bg-slate-800 dark:text-slate-500 mb-3">
              <Clock className="h-6 w-6" />
            </div>
            <h3 className="text-sm font-semibold text-slate-900 dark:text-white">No timesheets found</h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 max-w-sm">
              No entries match the selected status filter or search keywords.
            </p>
          </div>
        ) : (
          <DataTable columns={columns} data={filteredTimesheets} keyField="id" />
        )}
      </div>

      {/* View Timesheet Details Modal */}
      <Modal
        isOpen={isViewModalOpen}
        onClose={() => setIsViewModalOpen(false)}
        title="Timesheet Submission Details"
      >
        {selectedTimesheet && (
          <div className="space-y-4 py-2 text-sm">
            <div className="flex items-center gap-3 bg-slate-50 p-4 rounded-xl dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700">
              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-blue-600 text-white font-bold text-lg">
                <Clock className="h-6 w-6" />
              </div>
              <div className="flex-1">
                <div className="flex items-center justify-between">
                  <h3 className="font-bold text-base text-slate-900 dark:text-white">
                    TS-{String(selectedTimesheet.id || '').substring(0, 8).toUpperCase()}
                  </h3>
                  <StatusBadge status={selectedTimesheet.status || 'Draft'} />
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                  Work Date: {selectedTimesheet.workDate || 'Not specified'}
                </p>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
              <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 space-y-1">
                <span className="text-slate-500 flex items-center gap-1 font-medium"><User className="h-3.5 w-3.5" /> Contractor</span>
                <span className="font-semibold text-slate-900 dark:text-white text-sm">
                  {selectedTimesheet.contractor?.user?.name || selectedTimesheet.contractorName || 'Contractor'}
                </span>
                <span className="text-[11px] text-slate-400 block">{selectedTimesheet.contractor?.jobRole || 'Engineer'}</span>
              </div>

              <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 space-y-1">
                <span className="text-slate-500 flex items-center gap-1 font-medium"><Briefcase className="h-3.5 w-3.5" /> Project</span>
                <span className="font-semibold text-slate-900 dark:text-white text-sm">
                  {selectedTimesheet.projectName || selectedTimesheet.project?.projectName || 'Enterprise Delivery'}
                </span>
                <span className="text-[11px] text-slate-400 block">Vendor: {selectedTimesheet.vendorName || selectedTimesheet.contractor?.vendor?.vendorName || 'Agency'}</span>
              </div>

              <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 space-y-1">
                <span className="text-slate-500 flex items-center gap-1 font-medium"><Clock className="h-3.5 w-3.5" /> Hours Logged</span>
                <div className="flex items-baseline gap-2">
                  <span className="font-bold text-primary-600 dark:text-primary-400 text-sm">
                    {selectedTimesheet.totalHours ?? 0} hrs Total
                  </span>
                  <span className="text-[11px] text-slate-500">
                    ({selectedTimesheet.standardHours ?? selectedTimesheet.totalHours ?? 0} regular / {selectedTimesheet.overtimeHours ?? 0} OT)
                  </span>
                </div>
              </div>

              <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 space-y-1">
                <span className="text-slate-500 flex items-center gap-1 font-medium"><DollarSign className="h-3.5 w-3.5" /> Billable Value</span>
                <span className="font-bold text-emerald-600 dark:text-emerald-400 text-sm">
                  ${((Number(selectedTimesheet.totalHours) || 0) * (Number(selectedTimesheet.contractor?.hourlyRate) || 65)).toLocaleString()}
                </span>
                <span className="text-[11px] text-slate-400 block">Rate: ${selectedTimesheet.contractor?.hourlyRate || 65}/hr</span>
              </div>
            </div>

            {selectedTimesheet.description && (
              <div className="rounded-lg border border-slate-200 dark:border-slate-700/80 p-3 text-xs space-y-1">
                <span className="font-semibold text-slate-700 dark:text-slate-300 block">Task Description & Deliverables:</span>
                <p className="text-slate-600 dark:text-slate-400 leading-relaxed">{selectedTimesheet.description}</p>
              </div>
            )}

            {selectedTimesheet.rejectionReason && (
              <div className="rounded-lg border border-red-200 bg-red-50 dark:bg-red-950/40 p-3.5 text-xs text-red-700 dark:text-red-300 space-y-1">
                <span className="font-bold block">Rejection Feedback:</span>
                <p className="leading-relaxed">{selectedTimesheet.rejectionReason}</p>
              </div>
            )}

            <div className="flex justify-end space-x-2.5 pt-3 border-t border-slate-200 dark:border-slate-800">
              <Button variant="outline" onClick={() => setIsViewModalOpen(false)}>
                Close
              </Button>

              {((role === 'MANAGER' || role === 'ADMIN') && 
                ((selectedTimesheet.status || '').toUpperCase() === 'SUBMITTED' || (selectedTimesheet.status || '').toUpperCase() === 'REVIEW REQUIRED')) && (
                <>
                  <Button 
                    variant="danger" 
                    onClick={() => {
                      setIsViewModalOpen(false);
                      openRejectModal(selectedTimesheet);
                    }}
                  >
                    Reject
                  </Button>
                  <Button 
                    onClick={() => handleApprove(selectedTimesheet)}
                    isLoading={actionLoading}
                  >
                    Approve Now
                  </Button>
                </>
              )}

              {((role === 'CONTRACTOR' || role === 'ADMIN') && 
                (selectedTimesheet.status || '').toUpperCase() === 'DRAFT') && (
                <Button 
                  onClick={() => {
                    setIsViewModalOpen(false);
                    handleSubmit(selectedTimesheet);
                  }}
                  isLoading={actionLoading}
                >
                  Submit for Approval
                </Button>
              )}
            </div>
          </div>
        )}
      </Modal>

      {/* Reject Reason Modal */}
      <Modal
        isOpen={isRejectModalOpen}
        onClose={() => setIsRejectModalOpen(false)}
        title="Reject Timesheet Entry"
      >
        <div className="space-y-4 py-2">
          <p className="text-sm text-slate-600 dark:text-slate-300">
            Please enter your feedback for rejecting timesheet of <strong className="text-slate-900 dark:text-white">{selectedTimesheet?.contractor?.user?.name || selectedTimesheet?.contractorName || 'Contractor'}</strong> on <strong className="text-slate-900 dark:text-white">{selectedTimesheet?.workDate}</strong>:
          </p>
          <FormInput
            label="Rejection Reason (Required)"
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="e.g. Unapproved overtime logged or deliverables need verification."
            required
          />
          <div className="flex justify-end space-x-3 pt-4 border-t border-slate-200 dark:border-slate-800">
            <Button variant="outline" onClick={() => setIsRejectModalOpen(false)}>Cancel</Button>
            <Button variant="danger" onClick={handleReject} isLoading={actionLoading} disabled={!rejectReason.trim()}>
              Confirm Reject
            </Button>
          </div>
        </div>
      </Modal>

      {/* Contractor Add Wizard */}
      <TimesheetWizardModal
        isOpen={isWizardOpen}
        onClose={() => setIsWizardOpen(false)}
        onSuccess={() => fetchTimesheetsList(true)}
      />
    </div>
  );
};