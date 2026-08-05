class Solution {
public:
    long long countInverse(vector<long long> &a, int l, int r){
        if(l >= r) return 0;
        int mid = l + (r-l)/2;
        long long count = countInverse(a,l,mid)+countInverse(a,mid+1,r);
        int j = mid+1;
        for(int i = l ; i <= mid ; i++){
            while(j <= r && a[j] <= a[i]){
                j++;
            }
            count += (j-mid-1);
        }
        vector<long long> temp;
        int i = l;
        j = mid+1;
        while(i <= mid && j <= r){
            if(a[i] <= a[j]){
                temp.push_back(a[i++]);
            }else{
                temp.push_back(a[j++]);
            }
        }
        while(i <= mid) temp.push_back(a[i++]);
        while(j <= r) temp.push_back(a[j++]);
        for(int i = 0 ; i < temp.size() ; i++){
            a[l+i] = temp[i];
        }
        return count;
    }
    long long countRatioSubarrays(vector<int>& nums, int a, int b) {
        int n = size(nums);
        vector<long long> prefix(n);
        prefix[0] = (nums[0]&1)?-a:b;
        int cnt = prefix[0]<=0;
        for(int i = 1 ; i < n; i++){
            prefix[i] = (nums[i]&1)?-a:b;
            prefix[i] += prefix[i-1];
            cout<<prefix[i]<<" ";
            if(prefix[i] <= 0) cnt++;
        }
        return countInverse(prefix,0,n-1)+cnt;
    }
};