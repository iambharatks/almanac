class Solution {
public:
    vector<int> countTasks(vector<int>& tasks, vector<int>& shifts) {
        int n = size(tasks);
        vector<long long> p(n);
        p[0] = 1LL*tasks[0];
        for(int i = 1 ; i < n ; i++){
            p[i] = p[i-1] + tasks[i];
        }
        int m = size(shifts);
        vector<int> res(m);
        int cur = 0;
        long long shiftSum = 0;
        for(int j = 0 ; j < m ; j++){
            int l = lower_bound(begin(p)+cur,end(p),shifts[j]+shiftSum+1) - begin(p);
            cur = l;
            res[j] = n-l;
            // cout<<cur<<" "<<shiftSum<<'\n';
            if(cur != n){
                shiftSum += shifts[j];
            }else{
                cur = 0;
                shiftSum = 0;
            }
        }
        return res;
    }
};